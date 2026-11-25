#!/usr/bin/env python3
"""Explicit, offline V35 -> squashed V1 history transition. Never executes application DDL/DML.

Connection uses libpq PG* environment variables. Default is a read-only check.
All web/collector/migrator processes must be stopped before --apply.
"""
import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import uuid
from datetime import datetime, timezone

BASE = Path(__file__).resolve().parent
DESCRIPTION = 'Stelody squashed V1 (legacy V35)'


class RebaselineError(Exception):
    pass


def sql(statement):
    environment = dict(os.environ)
    environment.setdefault('PGCONNECT_TIMEOUT', '15')
    try:
        result = subprocess.run(['psql', '-X', '--no-password', '-v', 'ON_ERROR_STOP=1', '-At'],
                                input=statement, text=True, capture_output=True,
                                env=environment, timeout=90)
    except subprocess.TimeoutExpired:
        raise RebaselineError('Database command timed out; inspect history before retrying and keep services stopped') from None
    if result.returncode:
        # DB diagnostics may contain credentials or application rows.
        raise RebaselineError('Database command failed; inspect history before retrying and keep services stopped')
    return result.stdout.strip()


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def expected_history():
    return json.loads((BASE / 'legacy-v35-history.json').read_text())


def validate_legacy(rows):
    entries = [{key: row[key] for key in ('version', 'script', 'checksum', 'type', 'success')}
               for row in rows if row['version'] is not None]
    if entries != expected_history() or any(
            row['version'] is None and (row['type'] != 'SCHEMA' or not row['success']) for row in rows):
        raise RebaselineError('Requires the exact successful legacy V1-V35 history; upgrade or investigate first')


def is_squashed(rows):
    return (len(rows) == 1 and rows[0]['version'] == '1' and rows[0]['type'] == 'BASELINE'
            and rows[0]['description'] == DESCRIPTION and rows[0]['success'] is True
            and rows[0]['script'] == 'V1__initial_schema.sql' and rows[0]['checksum'] is None)


def inspect(database):
    info = json.loads(sql("""SELECT json_build_object(
      'database',current_database(),'owner',current_user,
      'serverVersion',current_setting('server_version_num')::int,
      'schemaOwners',(SELECT json_agg(DISTINCT pg_get_userbyid(nspowner)) FROM pg_namespace
        WHERE nspname IN ('app','session')),
      'history',COALESCE((SELECT json_agg(h ORDER BY installed_rank)
        FROM app.flyway_schema_history h),'[]'::json));"""))
    if (info['database'] != database or info['schemaOwners'] != [info['owner']]
            or not 170000 <= info['serverVersion'] < 180000):
        raise RebaselineError('Database name, PostgreSQL 17 and schema owner must match explicitly')
    return info


def write_history(path, info, backup_reference):
    value = {'format': 1, 'database': info['database'], 'owner': info['owner'],
             'savedAt': datetime.now(timezone.utc).isoformat(), 'backupReference': backup_reference,
             'history': info['history']}
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    directory_descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory_descriptor)
    finally:
        os.close(directory_descriptor)


def load_history(path, info):
    if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise RebaselineError('History backup must be a private regular file (600)')
    value = json.loads(path.read_text())
    if (value.get('format') != 1 or value.get('database') != info['database']
            or value.get('owner') != info['owner']):
        raise RebaselineError('History backup belongs to a different database/owner')
    validate_legacy(value['history'])
    return value['history']


def transition(info, rows, runtime_role, restore_rows=None):
    # Table locks block application writes for the whole transaction. The app and collector must
    # stay stopped afterwards until the matching release is ready. No grants/tables/rows change
    # except the existing Flyway history rows, which are restored atomically on any failure.
    before = literal(json.dumps(rows))
    delimiter = '$rebaseline_' + uuid.uuid4().hex + '$'
    if restore_rows is None:
        replacement = """INSERT INTO app.flyway_schema_history
          (installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
          VALUES(1,'1',%s,'BASELINE','V1__initial_schema.sql',NULL,current_user,0,true);""" % literal(DESCRIPTION)
    else:
        replacement = """INSERT INTO app.flyway_schema_history
          SELECT * FROM jsonb_populate_recordset(NULL::app.flyway_schema_history,%s::jsonb);""" % literal(json.dumps(restore_rows))
    statement = """BEGIN;
      SET LOCAL lock_timeout='5s';
      SET LOCAL statement_timeout='30s';
      SET LOCAL standard_conforming_strings=on;
      SELECT pg_advisory_xact_lock(20261125,1);
      DO $check$ DECLARE t record; BEGIN
        IF current_database()<>%s OR current_user<>%s THEN RAISE EXCEPTION 'WRONG_DATABASE'; END IF;
        IF EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
          AND pid<>pg_backend_pid() AND usename IN (%s,'stelody_collector')) THEN
          RAISE EXCEPTION 'SERVICES_MUST_BE_STOPPED';
        END IF;
        FOR t IN SELECT n.nspname,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname IN ('app','session') AND c.relkind IN ('r','p')
          ORDER BY n.nspname,c.relname
        LOOP EXECUTE format('LOCK TABLE %%I.%%I IN SHARE ROW EXCLUSIVE MODE',t.nspname,t.relname); END LOOP;
        LOCK TABLE app.flyway_schema_history IN ACCESS EXCLUSIVE MODE;
        IF (SELECT COALESCE(jsonb_agg(to_jsonb(h) ORDER BY installed_rank),'[]'::jsonb)
          FROM app.flyway_schema_history h) IS DISTINCT FROM %s::jsonb THEN
          RAISE EXCEPTION 'HISTORY_CHANGED';
        END IF;
      END $check$;
      DELETE FROM app.flyway_schema_history;
      %s
      COMMIT;""".replace('$check$', delimiter)
    statement %= (literal(info['database']), literal(info['owner']), literal(runtime_role), before, replacement)
    # Inputs and saved history are data even if they contain dollar-quote delimiters.
    if statement.count(delimiter) != 2:
        raise RebaselineError('Cannot delimit transaction safely')
    sql(statement)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True)
    parser.add_argument('--runtime-role', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--services-stopped', action='store_true')
    parser.add_argument('--backup-reference')
    parser.add_argument('--history-backup', type=Path)
    parser.add_argument('--restore-history', type=Path)
    args = parser.parse_args()
    info = inspect(args.database)
    rows = info['history']
    if args.restore_history:
        if not is_squashed(rows):
            raise RebaselineError('Rollback requires unchanged squashed baseline; later migrations must not exist')
        restore_rows = load_history(args.restore_history, info)
    else:
        if is_squashed(rows):
            print(json.dumps({'status': 'ALREADY_REBASELINED', 'changed': False}))
            return
        validate_legacy(rows)
        restore_rows = None
    if not args.apply:
        print(json.dumps({'status': 'READY_TO_RESTORE_HISTORY' if restore_rows else 'READY_TO_REBASELINE',
                          'changed': False, 'database': info['database']}))
        return
    if not args.services_stopped or not args.backup_reference:
        raise RebaselineError('Apply requires stopped services and a verified backup reference')
    if restore_rows is None:
        if args.history_backup is None:
            raise RebaselineError('Apply requires --history-backup pointing to a new private file')
        write_history(args.history_backup, info, args.backup_reference)
    transition(info, rows, args.runtime_role, restore_rows)
    print(json.dumps({'status': 'LEGACY_HISTORY_RESTORED' if restore_rows else 'REBASELINED',
                      'changed': True, 'database': info['database']}))


if __name__ == '__main__':
    try:
        main()
    except RebaselineError as error:
        raise SystemExit('REBASELINE_FAILED: ' + str(error))
    except (OSError, ValueError, KeyError, TypeError):
        raise SystemExit('REBASELINE_FAILED: preconditions or transaction failed; keep services stopped')

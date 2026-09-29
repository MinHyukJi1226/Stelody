-- Grant only the access needed by the runtime datasource; ownership stays with Flyway.
GRANT USAGE ON SCHEMA app, session TO "${runtimeRole}";
GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE session.spring_session, session.spring_session_attributes
    TO "${runtimeRole}";

package com.stelody.playlist.web;

public class PlaylistException extends RuntimeException {
  private final int status;
  private final String code;

  public PlaylistException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static PlaylistException invalid() {
    return new PlaylistException(400, "INVALID_PLAYLIST_REQUEST", "이름, 버전, 항목 또는 커서를 확인해 주세요");
  }

  public static PlaylistException missing() {
    return new PlaylistException(404, "PLAYLIST_NOT_FOUND", "플레이리스트 또는 항목을 찾을 수 없습니다");
  }

  public static PlaylistException changed() {
    return new PlaylistException(409, "PLAYLIST_CHANGED", "플레이리스트가 변경되었습니다. 새로고침해 주세요");
  }
}

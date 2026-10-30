package com.moveinsync.ev.service.api;

/** Opaque identifiers. An address or phone number is rejected. */
public final class OpaqueIds {

  private OpaqueIds() {
    throw new IllegalStateException("utility");
  }

  public static String require(String value, int maxLength) {
    if (value == null
        || value.isBlank()
        || value.length() > maxLength
        || value.indexOf('@') >= 0
        || value.startsWith("+")
        || !safe(value)) {
      throw new ApiException(400, "INVALID_ID", "An identifier is not opaque");
    }
    return value;
  }

  private static boolean safe(String value) {
    for (int index = 0; index < value.length(); index++) {
      char symbol = value.charAt(index);
      boolean ok =
          (symbol >= 'A' && symbol <= 'Z')
              || (symbol >= 'a' && symbol <= 'z')
              || (symbol >= '0' && symbol <= '9')
              || symbol == '_'
              || symbol == '-';
      if (!ok) {
        return false;
      }
    }
    return true;
  }
}

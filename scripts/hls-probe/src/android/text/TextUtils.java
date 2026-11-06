package android.text;

/** 只为离线探针替换 android.jar 的桩实现，覆盖 media3 UriUtil 用到的那几个。 */
public class TextUtils {
    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().contentEquals(b);
    }
}

import android.webkit.MimeTypeMap;

/**
 * tools/emu/checks/3C.sh: what the platform's MIME table holds on a device, as formats.md §2 rule 4
 * states it. A documents provider names a new document with that table (FileUtils.splitFileName), so
 * the table decides whether "Save as" keeps the names the app suggests.
 *
 * It runs on the device as the shell user, where app_process gives a tool the same table as an app
 * (the framework's, from Android 11 on; libcore's MimeUtils before):
 *
 *   CLASSPATH=/data/local/tmp/pv-mime.jar app_process /system/bin PvMimeTable csv text/csv ...
 *
 * An argument with a slash is a MIME type, any other an extension. One line each:
 *   ext .csv -> text/comma-separated-values      (MimeTypeMap.getMimeTypeFromExtension)
 *   type text/csv -> .csv                        (MimeTypeMap.getExtensionFromMimeType)
 * with "none" where the table has no answer. The last line is "PVMIME OK", or "PVMIME ERROR ...".
 */
public final class PvMimeTable {
    private PvMimeTable() {
    }

    public static void main(String[] args) {
        int status = 1;
        try {
            MimeTypeMap map = MimeTypeMap.getSingleton();
            for (String arg : args) {
                if (arg.indexOf('/') >= 0) {
                    String extension = map.getExtensionFromMimeType(arg);
                    System.out.println("type " + arg + " -> " + (extension == null ? "none" : "." + extension));
                } else {
                    String type = map.getMimeTypeFromExtension(arg);
                    System.out.println("ext ." + arg + " -> " + (type == null ? "none" : type));
                }
            }
            System.out.println("PVMIME OK");
            status = 0;
        } catch (Throwable t) {
            System.out.println("PVMIME ERROR " + t);
        }
        System.out.flush();
        System.exit(status);
    }
}

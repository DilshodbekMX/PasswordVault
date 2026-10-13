import android.os.LocaleList;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * The phone's languages and what the phone's own ICU and fonts make of a language, for the language
 * checks of tools/emu/i18n/ (build plan 6.1; i18n.md §5). Run on a private emulator as the shell
 * user through app_process (tools/emu/i18n/lib.sh builds, pushes and runs it):
 *
 *   CLASSPATH=/data/local/tmp/pv-locale.jar app_process /system/bin PvLocale COMMAND [ARGS]
 *
 * Commands, each answering with its lines and then "PVLOCALE OK" (or "PVLOCALE ERROR <why>"):
 *   get                      the phone's languages now, as BCP 47 tags separated by commas
 *   set TAGS                 makes TAGS (comma-separated) the phone's languages, as Settings ›
 *                            Languages does (the configuration that Android keeps, persist.sys.locale
 *                            included), and waits up to 20 s until the configuration has them
 *   date SKELETON TAG [MS]   MS (default now) as the phone's ICU writes it for TAG with SKELETON
 *                            (DateFormat.getInstanceForSkeleton) in the phone's time zone: what the
 *                            app's Formatters show in that language
 *   icu                      the phone's ICU version
 *
 * The shell user holds CHANGE_CONFIGURATION and WRITE_SETTINGS, which Settings' own LocalePicker
 * needs; the call is made on the activity manager's binder directly, because LocalePicker names the
 * calling package from an app's thread, which app_process has not.
 */
public final class PvLocale {
    public static void main(String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException("no command");
            switch (args[0]) {
                case "get":
                    System.out.println(currentTags());
                    break;
                case "set":
                    need(args, 2);
                    set(args[1]);
                    System.out.println(currentTags());
                    break;
                case "date":
                    need(args, 3);
                    long ms = args.length > 3 ? Long.parseLong(args[3]) : System.currentTimeMillis();
                    android.icu.text.DateFormat format = android.icu.text.DateFormat.getInstanceForSkeleton(
                            args[1], android.icu.util.ULocale.forLanguageTag(args[2]));
                    System.out.println(format.format(new Date(ms)));
                    break;
                case "icu":
                    System.out.println(android.icu.util.VersionInfo.ICU_VERSION);
                    break;
                default:
                    throw new IllegalArgumentException("unknown command " + args[0]);
            }
            System.out.println("PVLOCALE OK");
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null) cause = cause.getCause();
            System.out.println("PVLOCALE ERROR " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static void need(String[] args, int count) {
        if (args.length < count) throw new IllegalArgumentException(args[0] + " needs " + (count - 1) + " argument(s)");
    }

    /** The activity manager's binder: ActivityManager.getService() from Android 8, ActivityManagerNative.getDefault() before. */
    private static Object activityManager() throws Exception {
        try {
            return Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        } catch (NoSuchMethodException e) {
            return Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        }
    }

    private static android.content.res.Configuration configuration(Object am) throws Exception {
        return (android.content.res.Configuration) am.getClass().getMethod("getConfiguration").invoke(am);
    }

    private static String currentTags() throws Exception {
        return configuration(activityManager()).getLocales().toLanguageTags();
    }

    /** What LocalePicker.updateLocales does (frameworks/base, com.android.internal.app.LocalePicker). */
    private static void set(String tags) throws Exception {
        LocaleList wanted = LocaleList.forLanguageTags(tags);
        if (wanted.isEmpty()) throw new IllegalArgumentException("no language in '" + tags + "'");
        Object am = activityManager();
        android.content.res.Configuration config = configuration(am);
        config.setLocales(wanted);
        android.content.res.Configuration.class.getField("userSetLocale").setBoolean(config, true);
        Method withAttribution = null;
        for (Method m : am.getClass().getMethods()) {
            if (m.getName().equals("updatePersistentConfigurationWithAttribution")) withAttribution = m;
        }
        if (withAttribution != null) {
            withAttribution.invoke(am, config, "com.android.shell", null);
        } else {
            am.getClass().getMethod("updatePersistentConfiguration", android.content.res.Configuration.class).invoke(am, config);
        }
        long end = System.currentTimeMillis() + 20_000;
        while (!currentTags().equals(wanted.toLanguageTags())) {
            if (System.currentTimeMillis() > end) {
                throw new IllegalStateException("the phone's languages are " + currentTags() + ", not " + wanted.toLanguageTags());
            }
            Thread.sleep(200);
        }
    }
}

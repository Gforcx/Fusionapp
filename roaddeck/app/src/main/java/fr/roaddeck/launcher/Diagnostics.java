package fr.roaddeck.launcher;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Read-only diagnostics, collected only on an explicit copy action on an IO worker. */
final class Diagnostics {
    private static final int MAX_LOG_BYTES = 32 * 1024;
    private static final int MAX_REPORT_CHARS = 24 * 1024;
    private static final int MAX_ANR_BYTES = 8 * 1024;
    private static final long LOG_TIMEOUT_MS = 3000;
    private Diagnostics() { }

    static String report(Context context, JSONObject screen) {
        StringBuilder report = new StringBuilder("ROADDECK — RAPPORT LOCAL\n");
        report.append("Créé : ").append(time(System.currentTimeMillis())).append('\n');
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            report.append("Version : ").append(oneLine(info.versionName, 100)).append(" (").append(code).append(")\n");
        } catch (Exception error) {
            report.append("Version : indisponible (").append(error.getClass().getSimpleName()).append(")\n");
        }
        report.append("Application : ").append(context.getPackageName()).append('\n');
        report.append("Appareil : ").append(oneLine(Build.MANUFACTURER, 80)).append(' ')
                .append(oneLine(Build.MODEL, 100)).append('\n');
        report.append("Android : ").append(oneLine(Build.VERSION.RELEASE, 40)).append(" / API ")
                .append(Build.VERSION.SDK_INT).append('\n');
        report.append("Build : ").append(oneLine(Build.DISPLAY, 160)).append('\n');
        report.append("Correctif de sécurité : ").append(oneLine(Build.VERSION.SECURITY_PATCH, 40)).append('\n');
        report.append("PID actuel : ").append(Process.myPid()).append('\n');
        report.append("Fenêtre : ").append(screen.optInt("width")).append(" × ").append(screen.optInt("height"))
                .append(" ; multi-fenêtre : ").append(yes(screen.optBoolean("multiWindow"))).append('\n');
        report.append("Autorisation d’accessibilité enregistrée : ").append(yes(screen.optBoolean("accessibilityGranted"))).append('\n');
        report.append("Service connecté : ").append(yes(screen.optBoolean("accessibilityConnected"))).append('\n');
        report.append("Waze installé : ").append(yes(screen.optBoolean("wazeInstalled"))).append('\n');
        JSONObject panel = screen.optJSONObject("wazePanel");
        report.append("État du panneau : ").append(panel == null ? "indisponible" : panel.toString()).append('\n');
        report.append("\nDERNIERS ARRÊTS DU PROCESSUS ROADDECK\n");
        if (Build.VERSION.SDK_INT >= 30) Api30.appendExitHistory(context, report);
        else report.append("Historique système indisponible sur Android ").append(Build.VERSION.RELEASE)
                .append(" (API 30 minimum).\n");
        report.append("\nTRACES JAVA DU BUFFER CRASH\n");
        report.append(readOwnCrashFrames(context.getPackageName()));
        report.append("\nLes raisons d’arrêt sont celles enregistrées par Android ; elles ne prouvent pas à elles seules la cause d’une déconnexion actuelle.\n");
        report.append("Rapport local : aucun identifiant matériel unique, secret Tuya, message, contenu de page ou description libre d’arrêt n’est inclus.\n");
        if (report.length() > MAX_REPORT_CHARS) return report.substring(0, MAX_REPORT_CHARS) + "\n[Rapport limité]\n";
        return report.toString();
    }

    private static String yes(boolean value) { return value ? "oui" : "non"; }

    private static String oneLine(String value, int maximum) {
        if (value == null) return "inconnu";
        String text = value.replaceAll("[\\p{Cntrl}]", " ");
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private static String time(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }

    @TargetApi(30)
    private static final class Api30 {
        private static final AtomicBoolean traceReaderBusy = new AtomicBoolean();

        static void appendExitHistory(Context context, StringBuilder report) {
            try {
                ActivityManager manager = context.getSystemService(ActivityManager.class);
                if (manager == null) { report.append("Service système indisponible.\n"); return; }
                // The API can include an external WebView process: filter to our own UID/name.
                List<ApplicationExitInfo> records = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 20);
                int count = 0;
                ApplicationExitInfo latestAnr = null;
                for (ApplicationExitInfo record : records) {
                    if (record == null || record.getPackageUid() != Process.myUid()
                            || record.getRealUid() != Process.myUid()) continue;
                    String name = record.getProcessName();
                    if (name == null || !(name.equals(context.getPackageName())
                            || name.startsWith(context.getPackageName() + ":"))) continue;
                    if (name.equals(context.getPackageName()) && record.getReason() == ApplicationExitInfo.REASON_ANR
                            && (latestAnr == null || record.getTimestamp() > latestAnr.getTimestamp())) latestAnr = record;
                    if (count == 5) continue;
                    report.append(++count).append(". ").append(time(record.getTimestamp()))
                            .append(" ; PID ").append(record.getPid()).append(" ; ").append(oneLine(name, 180))
                            .append(" ; raison=").append(reason(record.getReason())).append(" (").append(record.getReason())
                            .append(") ; status=").append(record.getStatus()).append('\n');
                }
                if (count == 0) report.append("Aucun arrêt de notre processus disponible dans l’historique conservé par Android.\n");
                report.append("Status : code de sortie pour EXIT_SELF, numéro du signal pour SIGNALED ; valeur brute autrement.\n");
                report.append("\nPILE DU DERNIER ARRÊT ANR DU PROCESSUS PRINCIPAL\n");
                if (latestAnr == null) report.append("Aucun arrêt ANR du processus principal parmi les enregistrements disponibles.\n");
                else {
                    report.append(time(latestAnr.getTimestamp())).append(" ; PID ").append(latestAnr.getPid()).append('\n');
                    report.append(readAnrWithDeadline(latestAnr, context.getPackageName()));
                }
            } catch (Exception error) {
                report.append("Historique indisponible (").append(error.getClass().getSimpleName()).append(").\n");
            }
        }

        private static String readAnrWithDeadline(ApplicationExitInfo record, String packageName) {
            if (!traceReaderBusy.compareAndSet(false, true))
                return "Trace indisponible : une lecture précédente n’est pas encore terminée.\n";
            FutureTask<String> task = new FutureTask<>(() -> readAnr(record, packageName));
            Thread reader = new Thread(() -> {
                try { task.run(); }
                finally { traceReaderBusy.set(false); }
            }, "RoadDeck-AnrTrace");
            reader.setDaemon(true);
            try {
                reader.start();
                return task.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException error) {
                task.cancel(true);
                return "Trace indisponible : attente limitée à 3 secondes, interruption demandée.\n";
            } catch (InterruptedException error) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                return "Trace indisponible : demande interrompue.\n";
            } catch (Exception error) {
                task.cancel(true);
                if (!reader.isAlive()) traceReaderBusy.set(false);
                return "Trace indisponible (" + error.getClass().getSimpleName() + ").\n";
            }
        }

        private static String readAnr(ApplicationExitInfo record, String packageName) {
            // API30 may return null. Only ANR text is requested, never native-crash protobufs.
            try (InputStream stream = record.getTraceInputStream()) {
                if (stream == null) return "Trace ANR non conservée ou non fournie par Android.\n";
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                while (bytes.size() < MAX_ANR_BYTES && !Thread.currentThread().isInterrupted()) {
                    int count = stream.read(buffer, 0, Math.min(buffer.length, MAX_ANR_BYTES - bytes.size()));
                    if (count < 0) break;
                    if (count == 0) break;
                    bytes.write(buffer, 0, count);
                }
                if (Thread.currentThread().isInterrupted()) return "Lecture ANR interrompue.\n";
                String filtered = filterOwnAnrFrames(bytes.toString(StandardCharsets.UTF_8.name()), packageName, record.getPid());
                return (filtered.isEmpty() ? "Aucune pile main/RenderThread identifiable dans l’extrait ; format non reconnu ou contenu absent.\n" : filtered)
                        + (bytes.size() == MAX_ANR_BYTES ? "[Extrait limité à 8 Kio ; trace possiblement incomplète]\n" : "")
                        + "Seuls les états reconnus et les noms de classes/méthodes sont conservés ; ces piles ne prouvent pas à elles seules la cause.\n";
            } catch (Exception error) {
                return "Trace ANR indisponible (" + error.getClass().getSimpleName() + ").\n";
            }
        }

        private static String reason(int reason) {
            switch (reason) {
                case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
                case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
                case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
                case ApplicationExitInfo.REASON_CRASH: return "CRASH";
                case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
                case ApplicationExitInfo.REASON_ANR: return "ANR";
                case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
                case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
                case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
                case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
                case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
                case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
                case ApplicationExitInfo.REASON_OTHER: return "OTHER";
                case 14: return "FREEZER";
                case 15: return "PACKAGE_STATE_CHANGE";
                case 16: return "PACKAGE_UPDATED";
                default: return "UNKNOWN";
            }
        }
    }

    /** Strict ART text subset: exact historical PID/process, main/RenderThread only, no free text. */
    static String filterOwnAnrFrames(String raw, String processName, int expectedPid) {
        if (raw == null || processName == null || expectedPid <= 0) return "";
        if (raw.length() > MAX_ANR_BYTES) raw = raw.substring(0, MAX_ANR_BYTES);
        Pattern processHeader = Pattern.compile("^----- pid (\\d+) at .+ -----$");
        Pattern javaThread = Pattern.compile("^\"(main|RenderThread)\" (?:daemon )?prio=\\d+ tid=\\d+ ([A-Za-z]+)\\s*$");
        Pattern nativeThread = Pattern.compile("^\"(main|RenderThread)\" sysTid=\\d+\\s*$");
        Pattern javaFrame = Pattern.compile("^\\s*at\\s+([A-Za-z_$][A-Za-z0-9_.$]*(?:\\.<(?:init|clinit)>)?)\\([^\\r\\n]*\\)\\s*$");
        Pattern nativeFrame = Pattern.compile("^\\s*native:\\s*#\\d+\\s+pc\\s+[0-9a-fA-F]+\\s+\\S+\\s+\\(([A-Za-z_][A-Za-z0-9_:~]*)(?:\\(|\\+|\\))");
        boolean ownPid = false, ownProcess = false, selectedThread = false;
        int frames = 0;
        StringBuilder clean = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            Matcher header = processHeader.matcher(line);
            if (header.matches()) {
                ownPid = Integer.toString(expectedPid).equals(header.group(1));
                ownProcess = false; selectedThread = false; continue;
            }
            if (line.startsWith("-----")) { ownPid = false; ownProcess = false; selectedThread = false; continue; }
            if (line.startsWith("Cmd line:")) {
                ownProcess = ownPid && line.equals("Cmd line: " + processName);
                selectedThread = false; continue;
            }
            if (!ownProcess) continue;
            if (line.startsWith("\"")) {
                selectedThread = false; frames = 0;
                Matcher thread = javaThread.matcher(line);
                Matcher nativeOnly = nativeThread.matcher(line);
                if (thread.matches()) {
                    String state = thread.group(2);
                    if (!state.matches("Native|Runnable|Blocked|Waiting|TimedWaiting|Sleeping|Suspended|WaitingForGcToComplete|WaitingForCheckPointsToRun")) state = "unknown";
                    clean.append(thread.group(1)).append(" ; state=").append(state).append('\n');
                    selectedThread = true;
                } else if (nativeOnly.matches()) {
                    clean.append(nativeOnly.group(1)).append(" ; state=unknown\n");
                    selectedThread = true;
                }
                continue;
            }
            if (!selectedThread || frames >= 32) continue;
            Matcher frame = javaFrame.matcher(line);
            Matcher nativeCall = nativeFrame.matcher(line);
            if (frame.matches()) { clean.append("  at ").append(frame.group(1)).append('\n'); frames++; }
            else if (nativeCall.find()) { clean.append("  native ").append(nativeCall.group(1)).append('\n'); frames++; }
        }
        return clean.toString();
    }

    private static String readOwnCrashFrames(String packageName) {
        java.lang.Process child = null;
        boolean limited = false;
        boolean timedOut = false;
        try {
            child = new ProcessBuilder("logcat", "-b", "crash", "-d", "-v", "threadtime",
                    "--uid=" + Process.myUid(), "-t", "300").redirectErrorStream(true).start();
            long deadline = SystemClock.elapsedRealtime() + LOG_TIMEOUT_MS;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = child.getInputStream()) {
                byte[] buffer = new byte[2048];
                while (SystemClock.elapsedRealtime() < deadline && bytes.size() < MAX_LOG_BYTES) {
                    int available = stream.available();
                    if (available > 0) {
                        int count = stream.read(buffer, 0, Math.min(buffer.length,
                                Math.min(available, MAX_LOG_BYTES - bytes.size())));
                        if (count < 0) break;
                        bytes.write(buffer, 0, count);
                    } else if (!child.isAlive()) break;
                    else SystemClock.sleep(20);
                }
                limited = bytes.size() >= MAX_LOG_BYTES;
                timedOut = child.isAlive() && SystemClock.elapsedRealtime() >= deadline;
            }
            if (!child.isAlive() && child.exitValue() != 0)
                return "Indisponibles : accès refusé ou commande non prise en charge (code " + child.exitValue() + ").\n";
            String filtered = filterOwnJavaFrames(bytes.toString(StandardCharsets.UTF_8.name()), packageName);
            if (filtered.isEmpty()) return timedOut ? "Indisponibles : délai maximal de 3 secondes atteint.\n"
                    : "Aucune trace Java de RoadDeck accessible dans ce buffer. Cela n’exclut pas un arrêt antérieur.\n";
            return filtered + (limited ? "[Lecture limitée à 32 Ko]\n" : "")
                    + (timedOut ? "[Lecture arrêtée après 3 secondes]\n" : "");
        } catch (Exception error) {
            return "Indisponibles (" + error.getClass().getSimpleName() + ").\n";
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }

    /** Keeps process headers, exception class names and stack frames; never exception messages. */
    static String filterOwnJavaFrames(String raw, String packageName) {
        Pattern line = Pattern.compile("^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(\\d+)\\s+\\d+\\s+[A-Z]\\s+AndroidRuntime\\s*:\\s?(.*)$");
        Pattern process = Pattern.compile("^Process:\\s*(" + Pattern.quote(packageName)
                + "(?::[A-Za-z0-9_.$]+)?),\\s*PID:\\s*(\\d+)\\s*$");
        Pattern exception = Pattern.compile("^(?:(Caused by|Suppressed):\\s*)?([A-Za-z_$][A-Za-z0-9_.$]*)(?::.*)?$");
        Pattern frame = Pattern.compile("^\\s*at\\s+([A-Za-z_$][A-Za-z0-9_.$]*)\\(([A-Za-z0-9_.$/-]+(?::\\d+)?|Native Method|Unknown Source)\\)\\s*$");
        Map<String, CrashFrames> crashes = new LinkedHashMap<>();
        for (String text : raw.split("\\r?\\n")) {
            Matcher parsed = line.matcher(text);
            if (!parsed.matches()) continue;
            String pid = parsed.group(2), message = parsed.group(3).trim();
            if (message.startsWith("FATAL EXCEPTION")) crashes.remove(pid);
            Matcher header = process.matcher(message);
            if (header.matches() && pid.equals(header.group(2))) {
                if (crashes.size() == 5 && !crashes.containsKey(pid)) crashes.remove(crashes.keySet().iterator().next());
                crashes.put(pid, new CrashFrames(parsed.group(1), pid));
                continue;
            }
            // A PID can be reused. An unverified process header ends its prior ownership.
            if (message.startsWith("Process:")) { crashes.remove(pid); continue; }
            CrashFrames crash = crashes.get(pid);
            if (crash == null || crash.lines.size() >= 24) continue;
            Matcher type = exception.matcher(message);
            if (type.matches()) {
                String name = type.group(2);
                if (name.endsWith("Exception") || name.endsWith("Error") || name.endsWith("Throwable"))
                    crash.lines.add((type.group(1) == null ? "" : type.group(1) + ": ") + name);
                continue;
            }
            Matcher call = frame.matcher(message);
            if (call.matches()) crash.lines.add("  at " + call.group(1) + "(" + call.group(2) + ")");
        }
        StringBuilder clean = new StringBuilder();
        for (CrashFrames crash : crashes.values()) {
            if (crash.lines.isEmpty()) continue;
            clean.append(crash.timestamp).append(" ; PID ").append(crash.pid).append('\n');
            for (String stackFrame : crash.lines) clean.append(stackFrame).append('\n');
        }
        return clean.toString();
    }

    private static final class CrashFrames {
        final String timestamp, pid;
        final List<String> lines = new ArrayList<>();
        CrashFrames(String timestamp, String pid) { this.timestamp = timestamp; this.pid = pid; }
    }
}

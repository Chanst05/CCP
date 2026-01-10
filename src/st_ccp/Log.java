package st_ccp;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Log {
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    public static synchronized void info(String actor, String message) {
        String time = TS.format(new Date());
        String thread = Thread.currentThread().getName();
        System.out.printf("[%s] [%s] %s: %s%n", time, thread, actor, message);
    }
}
package seraphina.seraphina_lib.logger;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

/**
 * @author Seraphina
 * <p>
 * 日志工厂类，提供带有颜色和格式化功能的控制台日志输出。
 * <p>
 * 该类采用工厂模式，每个实例对应一个特定的日志前缀（通常是类名），
 * 输出的日志包含时间戳、前缀、日志级别和消息内容，并支持 ANSI 颜色高亮。
 * </p>
 *
 * <p><strong>特性：</strong></p>
 * <ul>
 *   <li>支持多种日志级别（TRACE, INFO, WARN, ERROR, SUCCESS, DEBUG）</li>
 *   <li>自动为不同级别添加颜色标识，便于在控制台中快速识别</li>
 *   <li>支持格式化参数，兼容 {@code {}} 和 {@code %s} 两种占位符风格</li>
 *   <li>线程安全的日期格式化（每个实例独立持有 {@link SimpleDateFormat}）</li>
 * </ul>
 *
 * <p><strong>使用示例：</strong></p>
 * <pre>
 * private static final LoggerFactory LOGGER = LoggerFactory.getLogger("MyClass");
 *
 * LOGGER.info("应用启动成功");
 * LOGGER.debug("用户ID: {}", userId);
 * LOGGER.warn("配置项 '{}' 未找到，使用默认值", key);
 * LOGGER.error("操作失败: {}", e.getMessage());
 * LOGGER.success("数据导出完成，共 {} 条记录", count);
 * </pre>
 */
public class Logger {
    /**
     * 日期格式化器，用于生成统一格式的时间戳。
     * 格式：{@code yyyy-MM-dd HH:mm:ss}
     */
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat FILE_DATE_FORMAT = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private static final Random RANDOM = new Random();
    private static PrintWriter fileWriter;
    /**
     * 使用 UTF-8 编码的控制台输出流，解决 Windows 下中文乱码问题。
     */
    private static final PrintStream UTF8_OUT;
    /** 是否启用 ANSI 彩色日志。Windows 11 以下的命令行禁用颜色，避免输出乱码控制字符。 */
    private static final boolean ENABLE_COLOR;
    // ANSI 颜色代码常量
    static final String RESET = "\u001B[0m";
    /** 红色，用于 ERROR 级别 */
    static final String RED = "\u001B[31m";
    /** 绿色，用于 SUCCESS 级别 */
    static final String GREEN = "\u001B[32m";
    /** 黄色，用于 WARN 级别 */
    static final String YELLOW = "\u001B[33m";
    /** 蓝色，用于 DEBUG 级别 */
    static final String BLUE = "\u001B[34m";
    /** 青色，用于 INFO 级别 */
    static final String CYAN = "\u001B[36m";
    /** 灰色，用于 TRACE 级别 */
    static final String GRAY = "\u001B[90m";
    /** 紫色，用于时间戳 */
    static final String PURPLE = "\u001B[35m";
    
    /** 当前日志记录器的名称/前缀 */
    private final String name;
    
    /**
     * 构造方法，初始化日志记录器。
     * <p><strong>注意：</strong>应通过 {@link LoggerFactory#getLogger(String)} 静态方法获取实例，而非直接调用构造器</p>
     *
     * @param name 日志前缀名称
     */
    Logger(String name) {
        this.name = name;
    }
    /**
     * 内部日志输出方法，格式化并打印日志到控制台。
     * <p>
     * 输出格式：[时间戳] [Raim Client][前缀]-级别: 消息内容
     * </p>
     *
     * @param level 日志级别文本（如 INFO, WARN）
     * @param levelColor 该级别的 ANSI 颜色代码
     * @param message 日志消息模板，支持 {} 占位符
     * @param args 格式化参数，用于替换消息中的占位符
     */
    private void log(String level, String levelColor, String message, Object... args) {
        String timestamp = dateFormat.format(new Date());

        String threadName = Thread.currentThread().getName();

        String formattedMessage = formatMessage(message, args);

        if (ENABLE_COLOR) {
            UTF8_OUT.printf(
                    "%s[%s] [%s] %s (%s) %s-%s : %s%s%n",
                    PURPLE,           // 时间戳颜色（紫色）
                    timestamp,        // 时间戳
                    threadName,       // 线程名称
                    GREEN,            // 前缀颜色（绿色）
                    name,             // 日志前缀
                    levelColor,       // 级别颜色（根据级别变化）
                    level,            // 级别文本
                    RESET,            // 重置颜色（确保消息内容为默认颜色）
                    formattedMessage  // 格式化后的消息内容
            );
        } else {
            UTF8_OUT.printf(
                    "[%s] [%s] (%s) -%s : %s%n",
                    timestamp,
                    threadName,
                    name,
                    level,
                    formattedMessage
            );
        }
        String fileOutput = String.format(
                "[%s] [%s] (%s) -%s : %s",
                timestamp,
                threadName,        // 时间戳
                name,             // 日志前缀
                level,            // 级别文本
                formattedMessage  // 格式化后的消息内容
        );
        if (fileWriter != null) {
            synchronized (Logger.class) {
                fileWriter.println(fileOutput);
            }
        }
    }
    
    /**
     * 格式化日志消息，支持两种占位符风格。
     * <p>
     * 优先使用 {@link String#format(String, Object...)}，如果失败则简单拼接。
     * 自动将 {@code {}} 替换为 {@code %s} 以兼容 Slf4j 风格。
     * </p>
     *
     * @param message 消息模板，可包含 {} 或 %s 占位符
     * @param args 用于替换占位符的参数数组
     * @return 格式化后的消息字符串
     */
    private String formatMessage(String message, Object[] args) {
        if (args.length == 0) return message;
        try {
            return String.format(message.replace("{}", "%s"), args);
        } catch (Exception e) {
            return message + " [Formatting Failed: " + e.getMessage() + "]";
        }
    }
    
    /**
     * 输出 TRACE 级别日志（最低级别，通常用于详细调试）。
     *
     * @param message 日志消息
     */
    public void trace(String message) {
        log("TRACE", GRAY, message);
    }
    
    /**
     * 输出 INFO 级别日志（普通信息）。
     *
     * @param message 日志消息模板
     * @param args 格式化参数
     */
    public void info(String message, Object... args) {
        log("INFO", CYAN, message, args);
    }
    
    /**
     * 输出 WARN 级别日志（警告信息，不会中断程序）。
     *
     * @param message 日志消息模板
     * @param args 格式化参数
     */
    public void warn(String message, Object... args) {
        log("WARN", YELLOW, message, args);
    }
    
    /**
     * 输出 ERROR 级别日志（错误信息，程序可能继续运行）。
     *
     * @param message 日志消息模板
     * @param args 格式化参数
     */
    public void error(String message, Object... args) {
        log("ERROR", RED, message, args);
    }
    
    /**
     * 输出 SUCCESS 级别日志（成功信息，自定义级别）。
     *
     * @param message 日志消息模板
     * @param args 格式化参数
     */
    public void success(String message, Object... args) {
        log("SUCCESS", GREEN, message, args);
    }
    
    /**
     * 输出 DEBUG 级别日志（调试信息，用于开发阶段）。
     *
     * @param message 日志消息模板
     * @param args 格式化参数
     */
    public void debug(String message, Object... args) {
        log("DEBUG", BLUE, message, args);
    }
    
    /**
     * 输出异常堆栈信息。
     * <p>
     * 先使用 ERROR 级别记录异常消息，然后打印完整的堆栈跟踪。
     * </p>
     *
     * @param throwable 要记录的异常对象
     */
    public void exception(Throwable throwable) {
        error("Exception occurred: {}", throwable.getMessage());
        if (fileWriter != null) {
            synchronized (Logger.class) {
                fileWriter.println("[Exception Stack Trace]");
                throwable.printStackTrace(fileWriter);
                fileWriter.println();
            }
        }
//        throwable.printStackTrace(System.out);
    }
    
    /**
     * 输出 INFO 级别日志（无参数版本）。
     *
     * @param message 日志消息
     */
    public void info(String message) {
        log("INFO", CYAN, message);
    }
    
    /**
     * 输出 WARN 级别日志（无参数版本）。
     *
     * @param message 日志消息
     */
    public void warn(String message) {
        log("WARN", YELLOW, message);
    }
    
    /**
     * 输出 ERROR 级别日志（无参数版本）。
     *
     * @param message 日志消息
     */
    public void error(String message) {
        log("ERROR", RED, message);
    }
    
    /**
     * 输出 SUCCESS 级别日志（无参数版本）。
     *
     * @param message 日志消息
     */
    public void success(String message) {
        log("SUCCESS", GREEN, message);
    }
    
    /**
     * 输出 DEBUG 级别日志（无参数版本）。
     *
     * @param message 日志消息
     */
    public void debug(String message) {
        log("DEBUG", BLUE, message);
    }

    private static void initializeLogFile() {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("seraphina_lib/log");
            File logDir = dir.toFile();
            if (!logDir.exists()) {
                boolean created = logDir.mkdirs();
                if (!created) {
                    System.err.println("[Logger System] cannot create a log directory: " + logDir.getAbsolutePath());
                    return;
                }
            }

            String timestamp = FILE_DATE_FORMAT.format(new Date());
            int randomNum = RANDOM.nextInt(10000); // 0-9999
            String fileName = String.format("log_%s__%04d.log", timestamp, randomNum);

            File logFile = new File(logDir, fileName);
            String logFilePath = logFile.getAbsolutePath();

            fileWriter = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(logFile, true),
                            StandardCharsets.UTF_8
                    ),
                    true
            );

            System.out.println("[Logger System] log file created: " + logFilePath);

            fileWriter.println("========================================");
            fileWriter.println("Seraphina log files");
            fileWriter.println("Creation time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            fileWriter.println("File path: " + logFilePath);
            fileWriter.println("========================================");
            fileWriter.println();

        } catch (Exception e) {
            System.err.println("[Logger System] failed to initialize the log file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 判断当前系统是否适合输出 ANSI 彩色日志。
     * <p>
     * Windows 10 及更早版本的传统命令行对 ANSI 转义码支持不稳定，因此 Windows 11 以下禁用彩色日志；
     * 非 Windows 系统保持原有行为，继续启用彩色日志。
     * </p>
     *
     * @return {@code true} 表示启用 ANSI 颜色，{@code false} 表示使用纯文本日志
     */
    private static boolean shouldEnableColor() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("windows")) {
            return true;
        }

        if (osName.contains("windows 11")) {
            return true;
        }

        Integer buildNumber = getWindowsBuildNumber();
        return buildNumber != null && buildNumber >= 22000;
    }

    /**
     * 获取 Windows 构建号。Windows 11 的构建号从 22000 开始。
     * <p>
     * 某些 Java 版本在 Windows 11 上仍会将 {@code os.name} 报告为 {@code Windows 10}，
     * 因此优先通过 {@code os.version} 中可能存在的构建号进行判断。
     * </p>
     */
    private static Integer getWindowsBuildNumber() {
        String osVersion = System.getProperty("os.version", "");
        String[] parts = osVersion.split("\\.");
        if (parts.length >= 3) {
            try {
                return Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static {
        PrintStream tempOut;
        try {
            tempOut = new PrintStream(System.out, true, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            tempOut = System.out;
        }
        UTF8_OUT = tempOut;
        ENABLE_COLOR = shouldEnableColor();

        initializeLogFile();
    }
}
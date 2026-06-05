package seraphina.seraphina_lib.logger;

import java.text.SimpleDateFormat;

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
public class LoggerFactory {
    
    /**
     * 创建并返回指定前缀的日志记录器实例。
     *
     * @param prefix 日志前缀，通常使用类名（如 "MyClass"）
     * @return 配置好的 LoggerFactory 实例
     */
    public static Logger getLogger(String prefix) {
        return new Logger(prefix + "/");
    }
    
    public static Logger getLogger(Object object) {
        return new Logger(object.toString());
    }
    
    public static Logger getLogger(Class<?> klass) {
        return new Logger(abbreviateClassName(klass.getName()));
    }

    private static String abbreviateClassName(String className) {
        int lastDotIndex = className.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return className;
        }

        String packageName = className.substring(0, lastDotIndex);
        String simpleClassName = className.substring(lastDotIndex + 1);

        String[] parts = packageName.split("\\.");
        StringBuilder abbreviated = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                abbreviated.append('.');
            }
            String part = parts[i];
            if (part.length() >= 2) {
                abbreviated.append(part.substring(0, 2));
            } else {
                abbreviated.append(part);
            }
        }

        abbreviated.append('.').append(simpleClassName);
        return abbreviated.toString();
    }
}
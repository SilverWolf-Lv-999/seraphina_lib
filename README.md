# SeraphinaLib

SeraphinaLib 是一个面向 **Minecraft Forge 1.20.1** 的 JDK 17 库模组，主要提供一套基于 ModLauncher/ASM 的轻量级字节码注入能力（SeraMixin），并附带日志、反射/模块辅助、SVG/GIF 客户端渲染工具。

> 当前版本仍处于早期开发阶段，公开 API 可能继续调整。建议在自己的模组中固定依赖版本，并在升级前测试启动与核心注入点。

## 特性

- **SeraMixin 注入系统**
  - 通过 `ServiceLoader` 自动发现 `ISeraMixin` 提供者。
  - 支持 `@SeraMixin`、`@Inject`、`@Overwrite`、`@Redirect`、`@ReturnField`、`@Shadow`。
  - 支持优先级排序、条件应用、父类目标匹配。
  - 转换阶段运行在 ModLauncher `BEFORE` 阶段，匹配后自动计算栈帧。
- **日志工具**
  - `LoggerFactory` / `Logger` 提供 TRACE、INFO、WARN、ERROR、SUCCESS、DEBUG 等级。
  - 支持 `{}` / `%s` 占位符格式化、控制台颜色输出，以及 UTF-8 文件日志。
- **客户端图片工具**
  - `SvgImage`：把 SVG 资源光栅化为 Minecraft 动态纹理，支持部分 SVG 动画。
  - `GIFImage`：解析 GIF 帧并按原始延迟渲染动态纹理。
- **底层辅助**
  - `HelperLib`、`ModuleUtil`、`ClassUtils` 提供反射、模块开放、类字节读取和隐藏类定义等工具。

## 环境要求

| 项目 | 当前配置 |
| --- | --- |
| Minecraft | `1.20.1` |
| Forge | `47.4.20`，版本范围 `[47,)` |
| Java | `17` |
| Gradle Wrapper | `8.8` |
| License | `MIT` |

## 构建与运行

Windows:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
```

Linux/macOS:

```bash
./gradlew build
./gradlew runClient
./gradlew runServer
```

构建产物会输出到 `build/libs/`。当前工程没有配置公开 Maven 发布流程，如果要在其他模组中使用，可以先构建 jar，再把它作为本地依赖和运行时模组加入消费项目。

示例 ForgeGradle 本地依赖：

```gradle
dependencies {
    implementation fg.deobf(files("libs/seraphina_lib-0.0.1.jar"))
}
```

消费项目的 `mods.toml` 建议声明依赖：

```toml
[[dependencies.example_mod]]
modId = "seraphina_lib"
mandatory = true
versionRange = "[0.0.1,)"
ordering = "AFTER"
side = "BOTH"
```

## SeraMixin 快速接入

### 1. 创建 Mixin 提供者

在你的模组中实现 `ISeraMixin`，返回要注册的 Mixin 类名。

```java
package com.example.mixin;

import org.objectweb.asm.tree.ClassNode;
import seraphina.seraphina_lib.service.ISeraMixin;

public final class ExampleSeraMixinProvider implements ISeraMixin {
    @Override
    public String getMixinPath() {
        return "com.example.mixin.TargetExampleMixin";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean shouldApplyMixin(ClassNode targetClassNode, ClassNode mixinClassNode) {
        return true;
    }
}
```

`getPriority()` 数值越高，应用顺序越靠前。`shouldApplyMixin(...)` 可用于按目标类结构、环境或配置决定是否应用。

### 2. 注册 ServiceLoader 文件

创建资源文件：

```text
src/main/resources/META-INF/services/seraphina.seraphina_lib.service.ISeraMixin
```

文件内容写入提供者完整类名：

```text
com.example.mixin.ExampleSeraMixinProvider
```

SeraphinaLib 的启动服务会扫描这些 provider，并注册 provider 指向的 Mixin 类。

### 3. 编写 Mixin 类

```java
package com.example.mixin;

import com.example.TargetExample;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.util.CallBackInfo;
import seraphina.seraphina_lib.mixin.util.InsertPosition;

@SeraMixin(TargetExample.class)
public class TargetExampleMixin {
    @Inject(methodName = {"tick"}, desc = "()V", at = InsertPosition.HEAD)
    private void beforeTick(CallBackInfo callbackInfo) {
        // callbackInfo.cancel();
    }
}
```

`desc` 必须填写 JVM 方法描述符，例如 `()V`、`(I)Z`、`(Ljava/lang/String;)V`。方法名和描述符需要与当前 mappings 下的目标方法一致。

`@Inject` handler 参数按声明顺序自动填充：

- 实例目标方法可以声明目标类实例作为第一个参数。
- 后续参数会按目标方法参数顺序传入。
- 声明 `CallBackInfo` 参数时，可调用 `cancel()` 取消后续执行；有返回值方法可用 `setBackValue(...)` 指定返回值。

示例：

```java
@Inject(
    methodName = {"canUse"},
    desc = "(I)Z",
    at = InsertPosition.HEAD
)
private void beforeCanUse(TargetExample self, int level, CallBackInfo callbackInfo) {
    if (level < 0) {
        callbackInfo.setBackValue(false);
    }
}
```

## 注解速查

| 注解 | 作用 | 备注 |
| --- | --- | --- |
| `@SeraMixin(Target.class)` | 声明当前类要注入的目标类 | 必需 |
| `@Inject(methodName, desc, at)` | 在目标方法头部或返回前插入 handler | 当前主要支持 `HEAD`、`LAST` |
| `@Overwrite(methodName, desc)` | 用 Mixin 方法体替换目标方法体 | 谨慎使用，冲突风险较高 |
| `@Redirect(methodName, methodDesc, targetMethod, targetMethodDesc)` | 把目标方法中的指定调用重定向到 handler | handler 需要匹配调用参数/返回值 |
| `@ReturnField(field, type, isStatic, read, write)` | 拦截指定字段读写，并经由 public static handler 返回新值 | 实例字段 handler 形如 `(Target self, T value)T`，静态字段 handler 形如 `(T value)T` |
| `@Shadow("targetName")` | 把 Mixin 内字段/方法引用改写为目标类字段/方法 | `value` 为空时使用同名成员 |

以下注解目前更偏保留或实验性质：`@ASM`、`@Store`、`@PushArgs`、`@MapMethod`、`@NoReMapping`、`@FiledUse`、`@MethodUse`。其中 `@ASM` handler 在当前服务实现中会被忽略，不建议作为稳定功能依赖。

## 日志工具

```java
import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;

public final class Example {
    private static final Logger LOGGER = LoggerFactory.getLogger(Example.class);

    public void run(String userName) {
        LOGGER.info("Hello, {}", userName);
        LOGGER.warn("Config {} is missing", "example_key");
        LOGGER.success("Task finished");
    }
}
```

文件日志会写入游戏目录下的 `silent_love_sword/log/`。如果你准备把日志工具作为通用库使用，建议后续把日志目录改成可配置项。

## SVG / GIF 渲染

```java
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import seraphina.seraphina_lib.client.gif.GIFImage;
import seraphina.seraphina_lib.client.svg.SvgImage;

public final class ExampleScreenImages {
    private final SvgImage icon = new SvgImage(
        ResourceLocation.fromNamespaceAndPath("example_mod", "svg/icon.svg"),
        128,
        128
    );

    private final GIFImage loading = new GIFImage(
        ResourceLocation.fromNamespaceAndPath("example_mod", "textures/gui/loading.gif")
    );

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        icon.draw(graphics, 10, 10, 24, 24);
        loading.draw(graphics, 40, 10, 24, 24, 0.9F);
    }
}
```

SVG/GIF 资源通过 Minecraft `ResourceManager` 读取，因此路径需要放在对应命名空间的 assets 目录中。

## 项目结构

```text
src/main/java/seraphina/seraphina_lib/
├─ client/              SVG/GIF 动态纹理工具
├─ logger/              日志工具
├─ mixin/
│  ├─ annotation/       SeraMixin 注解
│  ├─ service/          ModLauncher 转换服务
│  └─ util/             注入位置与回调对象
├─ service/             ISeraMixin 扩展接口
└─ util/                反射、模块、类加载辅助
```

## 注意事项

- 该项目使用 JDK internal API、`Unsafe`、ModLauncher 内部结构和 ASM，Forge、Java 或 ModLauncher 升级后需要重点回归启动阶段。
- `@Inject`、`@Overwrite`、`@Redirect` 都依赖准确的方法名和 JVM 描述符，目标方法签名变化会导致注入失效。
- 多个模组同时修改同一目标方法时，建议通过 `ISeraMixin#getPriority()` 和更小范围的注入点降低冲突。
- 客户端图片工具依赖 Minecraft client 类，只应在客户端渲染代码中使用。

## 许可证

本项目基于 [MIT License](LICENSE) 发布。

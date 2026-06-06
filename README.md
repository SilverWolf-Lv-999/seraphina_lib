# SeraphinaLib

SeraphinaLib 是一个面向 **Minecraft Forge 1.20.1** 的 JDK 17 库模组，主要提供一套基于 ModLauncher/ASM 的轻量级字节码注入能力（SeraMixin），并附带日志、反射/模块辅助、SVG/GIF 客户端渲染工具。

> 当前版本仍处于早期开发阶段，公开 API 可能继续调整。建议在自己的模组中固定依赖版本，并在升级前测试启动与核心注入点。

## 特性

- **SeraMixin 注入系统**
  - 通过 `ServiceLoader` 自动发现 `ISeraMixin` 提供者。
  - 支持 `@SeraMixin`、`@Inject`、`@Overwrite`、`@Redirect`、`@ReturnField`、`@Shadow`、`@Accessor`、`@Invoker`。
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
import seraphina.seraphina_lib.mixin.util.InsertShift;

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

更精确的注入点可使用 `INVOKE`、`FIELD`、`NEW`、`JUMP`、`RETURN`、`TAIL` 等位置，并配合
`target`、`ordinal`、`opcode`、`shift`、`by` 定位具体指令：

```java
@Inject(
    methodName = {"tick"},
    desc = "()V",
    at = InsertPosition.INVOKE,
    target = "net/minecraft/world/entity/Entity/baseTick()V",
    shift = InsertShift.AFTER
)
private void afterBaseTick(CallBackInfo callbackInfo) {
}
```

`target` 支持 `owner/name(desc)ret`、`Lowner;name(desc)ret`、`owner/name:desc` 和
`NEW` 的类名形式；也可以拆开写 `owner`、`name`、`targetDesc`。`ordinal` 是匹配到的第几个指令，
`opcode` 可用 ASM opcode 进一步过滤，`by` 会在最终锚点上按真实字节码指令前后移动。
同一个 handler 上的 `@InjectPoint` 现在会作为 `CUSTOM` 的索引或定位配置参与解析。

### 4. 让目标类实现接口

Mixin 类可以实现你自己的接口。转换时 SeraMixin 会把接口加入目标类，并复制 Mixin 中没有
SeraMixin 专用注解的普通字段和方法。因此外部代码可以把目标实例强转为该接口使用。

接口示例：

```java
package com.example;

public interface ILivingEntity {
    float getF();
}
```

Mixin 示例：

```java
package com.example.mixin;

import net.minecraft.world.entity.LivingEntity;
import seraphina.seraphina_lib.mixin.annotation.CallbackInfo;
import seraphina.seraphina_lib.mixin.annotation.Inject;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;
import seraphina.seraphina_lib.mixin.util.CallBackInfo;
import seraphina.seraphina_lib.mixin.util.InsertPosition;
import com.example.ILivingEntity;

@SeraMixin(LivingEntity.class)
public class LivingEntityMixin implements ILivingEntity {
    private final LivingEntity sera$living = (LivingEntity) (Object) this;

    @Inject(methodName = {"getHealth"}, desc = "()F", at = InsertPosition.HEAD, callback = @CallbackInfo(callback = true))
    public void getHealth(CallBackInfo ci) {
        ci.setBackValue(20.0F);
    }

    @Override
    public float getF() {
        return sera$living.getHealth();
    }
}
```

使用示例：

```java
LivingEntity entity = ...;
float value = ((ILivingEntity) entity).getF();
```

带 `@Inject`、`@Overwrite`、`@Redirect`、`@ReturnField`、`@Shadow`、`@Accessor`、`@Invoker` 等 SeraMixin 注解的方法会按对应规则处理，
不会作为普通接口方法复制；像 `getF()` 这种无专用注解的普通方法会被复制到目标类。

### 5. 使用 Accessor / Invoker

`@Accessor` 和 `@Invoker` 推荐直接写在 `@SeraMixin` 接口上。SeraMixin 会把该接口加入目标类，并为接口方法生成字段访问或目标方法调用实现。

```java
package com.example.mixin;

import com.example.TargetExample;
import seraphina.seraphina_lib.mixin.annotation.Accessor;
import seraphina.seraphina_lib.mixin.annotation.Invoker;
import seraphina.seraphina_lib.mixin.annotation.SeraMixin;

@SeraMixin(TargetExample.class)
public interface TargetExampleAccess {
    @Accessor("value")
    int sera$getValue();

    @Accessor("value")
    void sera$setValue(int value);

    @Invoker("recalculate")
    boolean sera$invokeRecalculate(String key);
}
```

使用示例：

```java
TargetExample target = ...;
TargetExampleAccess access = (TargetExampleAccess) target;

int oldValue = access.sera$getValue();
access.sera$setValue(oldValue + 1);
boolean changed = access.sera$invokeRecalculate("main");
```

`@Accessor` 的 getter 必须是无参数且非 `void`，setter 必须是单参数且返回 `void`。`@Invoker` 方法签名默认就是目标方法签名；
如果访问静态字段或静态方法，可设置 `isStatic = true`。`value` 为空时会从 `getX`、`isX`、`setX`、`invokeX`、`callX`
等方法名推断目标成员名，但更建议显式填写目标名。

### 6. 修改常量、调用参数和局部变量

`@ModifyConstant` handler 形如 `(T value)T`，会把匹配到的常量替换为 handler 返回值。

```java
@ModifyConstant(methodName = {"getLimit"}, desc = "()I", constant = "10")
private int modifyLimit(int value) {
    return 20;
}
```

`@ModifyArgs` handler 形如 `(Args args)V`，在目标调用发生前修改参数。

```java
@ModifyArgs(
        methodName = {"renderName"},
        methodDesc = "(Ljava/lang/String;)V",
        targetMethod = {"com.example.Renderer.draw"},
        targetMethodDesc = "(Ljava/lang/String;I)V"
)
private void modifyDrawArgs(Args args) {
    args.set(0, "[Sera] " + args.<String>get(0));
    args.set(1, 0xFFFFFF);
}
```

`@ModifyVariable` handler 形如 `(T value)T`，可以按本地变量索引、opcode、ordinal 过滤 load/store 指令。

```java
@ModifyVariable(methodName = {"tick"}, desc = "()V", index = 1, store = true, load = false)
private float clampSpeed(float speed) {
    return Math.min(speed, 1.0F);
}
```

这三个注解都支持私有实例 handler；当目标方法是静态方法时，handler 必须是 `static`。

## 注解速查

| 注解 | 作用 | 备注 |
| --- | --- | --- |
| `@SeraMixin(Target.class)` | 声明当前类要注入的目标类 | 必需 |
| `@Inject(methodName, desc, at)` | 在目标方法或目标指令附近插入 handler | 支持 `HEAD`、`TAIL`、`RETURN`、`INVOKE`、`FIELD`、`NEW`、`JUMP`、`CUSTOM` |
| `@Overwrite(methodName, desc)` | 用 Mixin 方法体替换目标方法体 | 谨慎使用，冲突风险较高 |
| `@Redirect(methodName, methodDesc, targetMethod, targetMethodDesc)` | 把目标方法中的指定调用重定向到 handler | handler 需要匹配调用参数/返回值 |
| `@ModifyConstant(methodName, desc, constant)` | 把匹配常量交给 handler 返回替换值 | handler 形如 `(T)T`，可用 `type`、`ordinal`、`opcode` 过滤 |
| `@ModifyArgs(methodName, methodDesc, targetMethod, targetMethodDesc)` | 在目标调用前修改参数 | handler 形如 `(Args)V` |
| `@ModifyVariable(methodName, desc, index)` | 修改局部变量 load/store 值 | handler 形如 `(T)T`，可用 `type`、`ordinal`、`opcode` 过滤 |
| `@ReturnField(field, type, isStatic, read, write)` | 拦截指定字段读写，并经由 public static handler 返回新值 | 实例字段 handler 形如 `(Target self, T value)T`，静态字段 handler 形如 `(T value)T` |
| `@Shadow("targetName")` | 把 Mixin 内字段/方法引用改写为目标类字段/方法 | `value` 为空时使用同名成员 |
| `@Accessor("fieldName")` | 为目标字段生成 getter 或 setter | getter 无参数非 `void`，setter 单参数返回 `void`；静态字段设置 `isStatic = true` |
| `@Invoker("methodName")` | 为目标方法生成调用桥接方法 | 默认使用 invoker 自身描述符；静态方法设置 `isStatic = true` |

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

文件日志会写入游戏目录下的 `seraphina_lib/log/`。如果你准备把日志工具作为通用库使用，建议后续把日志目录改成可配置项。

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

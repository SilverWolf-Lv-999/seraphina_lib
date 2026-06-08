# SeraphinaLib

SeraphinaLib is a JDK 17 library mod for **Minecraft Forge 1.20.1**. It mainly provides a lightweight ModLauncher/ASM-based bytecode injection system named SeraMixin, along with logging utilities, reflection/module helpers, and SVG/GIF client rendering tools.

> This project is still in an early development stage. Public APIs may continue to change. Pin the dependency version in your own mod and test startup and core injection points before upgrading.

## Features

- **SeraMixin injection system**
  - Automatically discovers `ISeraMixin` providers through `ServiceLoader`.
  - Supports `@SeraMixin`, `@Inject`, `@Overwrite`, `@Redirect`, `@ReturnField`, `@Shadow`, `@Accessor`, and `@Invoker`.
  - Supports priority ordering, conditional application, and superclass target matching.
  - Runs during the ModLauncher `BEFORE` transformation phase and automatically computes stack frames after matching.
- **Logging utilities**
  - `LoggerFactory` / `Logger` provide TRACE, INFO, WARN, ERROR, SUCCESS, and DEBUG levels.
  - Supports `{}` / `%s` placeholder formatting, colored console output, and UTF-8 file logs.
- **Client image utilities**
  - `SvgImage`: rasterizes SVG resources into Minecraft dynamic textures and supports partial SVG animation.
  - `GIFImage`: parses GIF frames and renders dynamic textures with their original frame delays.
- **Low-level helpers**
  - `HelperLib`, `ModuleUtil`, and `ClassUtils` provide reflection, module opening, class byte reading, hidden class definition, and related utilities.

## Requirements

| Item | Current configuration |
| --- | --- |
| Minecraft | `1.20.1` |
| Forge | `47.4.20`, version range `[47,)` |
| Java | `17` |
| Gradle Wrapper | `8.8` |
| License | `MIT` |

## Build and Run

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

Build artifacts are written to `build/libs/`. The project does not currently configure a public Maven publishing workflow. If you want to use it from another mod, build the jar first, then add it to the consuming project as a local dependency and runtime mod.

Example ForgeGradle local dependency:

```gradle
dependencies {
    implementation fg.deobf(files("libs/seraphina_lib-0.0.1.jar"))
}
```

The consuming project's `mods.toml` should declare the dependency:

```toml
[[dependencies.example_mod]]
modId = "seraphina_lib"
mandatory = true
versionRange = "[0.0.1,)"
ordering = "AFTER"
side = "BOTH"
```

## SeraMixin Quick Start

### 1. Create a Mixin Provider

Implement `ISeraMixin` in your mod and return the Mixin class name you want to register.

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

A higher `getPriority()` value means the mixin is applied earlier. `shouldApplyMixin(...)` can decide whether to apply the mixin based on the target class structure, environment, or configuration.

### 2. Register the ServiceLoader File

Create the resource file:

```text
src/main/resources/META-INF/services/seraphina.seraphina_lib.service.ISeraMixin
```

Write the provider's fully qualified class name into the file:

```text
com.example.mixin.ExampleSeraMixinProvider
```

SeraphinaLib's startup service scans these providers and registers the Mixin classes referenced by them.

### 3. Write a Mixin Class

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

`desc` must be a JVM method descriptor, such as `()V`, `(I)Z`, or `(Ljava/lang/String;)V`. The method name and descriptor must match the target method under the current mappings.

`@Inject` handler parameters are filled automatically in declaration order:

- Instance target methods may declare the target class instance as the first parameter.
- Later parameters are passed in target method parameter order.
- When a `CallBackInfo` parameter is declared, you can call `cancel()` to stop later execution. For methods with return values, use `setBackValue(...)` to set the returned value.

Example:

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

For more precise injection points, use positions such as `INVOKE`, `FIELD`, `NEW`, `JUMP`, `RETURN`, and `TAIL`, together with `target`, `ordinal`, `opcode`, `shift`, and `by` to locate a specific instruction:

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

`target` supports `owner/name(desc)ret`, `Lowner;name(desc)ret`, `owner/name:desc`, and class-name forms for `NEW`. You can also split it into `owner`, `name`, and `targetDesc`. `ordinal` is the index of the matched instruction, `opcode` can further filter by ASM opcode, and `by` moves forward or backward from the final anchor by real bytecode instructions. `@InjectPoint` on the same handler is now used as the index or location configuration for `CUSTOM` parsing.

### 4. Make the Target Class Implement an Interface

A Mixin class can implement your own interface. During transformation, SeraMixin adds that interface to the target class and copies ordinary fields and methods from the Mixin that do not have SeraMixin-specific annotations. External code can then cast the target instance to that interface.

Interface example:

```java
package com.example;

public interface ILivingEntity {
    float getF();
}
```

Mixin example:

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

Usage example:

```java
LivingEntity entity = ...;
float value = ((ILivingEntity) entity).getF();
```

Methods annotated with SeraMixin annotations such as `@Inject`, `@Overwrite`, `@Redirect`, `@ReturnField`, `@Shadow`, `@Accessor`, and `@Invoker` are processed according to their own rules and are not copied as ordinary interface methods. Ordinary methods without special annotations, such as `getF()`, are copied into the target class.

### 5. Use Accessor / Invoker

`@Accessor` and `@Invoker` are best placed directly on a `@SeraMixin` interface. SeraMixin adds the interface to the target class and generates implementations that access fields or call target methods.

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

Usage example:

```java
TargetExample target = ...;
TargetExampleAccess access = (TargetExampleAccess) target;

int oldValue = access.sera$getValue();
access.sera$setValue(oldValue + 1);
boolean changed = access.sera$invokeRecalculate("main");
```

An `@Accessor` getter must have no parameters and a non-`void` return type. A setter must have one parameter and return `void`. An `@Invoker` method signature is the target method signature by default. If you need to access a static field or method, set `isStatic = true`. When `value` is empty, the target member name is inferred from method names such as `getX`, `isX`, `setX`, `invokeX`, and `callX`, but explicitly setting the target name is recommended.

### 6. Modify Constants, Call Arguments, and Local Variables

A `@ModifyConstant` handler has the shape `(T value)T`. It replaces the matched constant with the handler return value.

```java
@ModifyConstant(methodName = {"getLimit"}, desc = "()I", constant = "10")
private int modifyLimit(int value) {
    return 20;
}
```

A `@ModifyArgs` handler has the shape `(Args args)V`. It modifies arguments before the target call occurs.

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

A `@ModifyVariable` handler has the shape `(T value)T`. It can filter load/store instructions by local variable index, opcode, and ordinal.

```java
@ModifyVariable(methodName = {"tick"}, desc = "()V", index = 1, store = true, load = false)
private float clampSpeed(float speed) {
    return Math.min(speed, 1.0F);
}
```

All three annotations support private instance handlers. If the target method is static, the handler must be `static`.

## Annotation Reference

| Annotation | Purpose | Notes |
| --- | --- | --- |
| `@SeraMixin(Target.class)` | Declares the target class for the current class | Required |
| `@Inject(methodName, desc, at)` | Inserts a handler into the target method or near a target instruction | Supports `HEAD`, `TAIL`, `RETURN`, `INVOKE`, `FIELD`, `NEW`, `JUMP`, and `CUSTOM` |
| `@Overwrite(methodName, desc)` | Replaces the target method body with the Mixin method body | Use carefully; conflict risk is high |
| `@Redirect(methodName, methodDesc, targetMethod, targetMethodDesc)` | Redirects a specified call inside the target method to the handler | The handler must match the call parameters and return value |
| `@ModifyConstant(methodName, desc, constant)` | Passes a matched constant to the handler and replaces it with the return value | Handler shape is `(T)T`; can filter with `type`, `ordinal`, and `opcode` |
| `@ModifyArgs(methodName, methodDesc, targetMethod, targetMethodDesc)` | Modifies arguments before the target call | Handler shape is `(Args)V` |
| `@ModifyVariable(methodName, desc, index)` | Modifies local variable load/store values | Handler shape is `(T)T`; can filter with `type`, `ordinal`, and `opcode` |
| `@ReturnField(field, type, isStatic, read, write)` | Intercepts reads/writes of the specified field and returns a new value through a public static handler | Instance field handler shape is `(Target self, T value)T`; static field handler shape is `(T value)T` |
| `@Shadow("targetName")` | Rewrites field/method references inside the Mixin to target class fields/methods | When `value` is empty, the member with the same name is used |
| `@Accessor("fieldName")` | Generates a getter or setter for a target field | Getter has no parameters and returns non-`void`; setter has one parameter and returns `void`; set `isStatic = true` for static fields |
| `@Invoker("methodName")` | Generates a bridge method that calls a target method | Uses the invoker's own descriptor by default; set `isStatic = true` for static methods |

The following annotations are currently reserved or experimental: `@ASM`, `@Store`, `@PushArgs`, `@MapMethod`, `@NoReMapping`, `@FiledUse`, and `@MethodUse`. In the current service implementation, `@ASM` handlers are ignored, so they should not be treated as a stable feature.

## Logging Utilities

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

File logs are written to `seraphina_lib/log/` under the game directory. If you plan to use the logging utilities as a general-purpose library, consider making the log directory configurable in a later revision.

## SVG / GIF Rendering

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

SVG/GIF resources are loaded through Minecraft's `ResourceManager`, so paths must be placed under the `assets` directory for the corresponding namespace.

## Project Structure

```text
src/main/java/seraphina/seraphina_lib/
+-- client/              SVG/GIF dynamic texture utilities
+-- logger/              Logging utilities
+-- mixin/
|   +-- annotation/       SeraMixin annotations
|   +-- service/          ModLauncher transformation service
|   +-- util/             Injection positions and callback objects
+-- service/             ISeraMixin extension interface
+-- util/                Reflection, module, and class loading helpers
```

## Notes

- This project uses JDK internal APIs, `Unsafe`, ModLauncher internals, and ASM. After upgrading Forge, Java, or ModLauncher, pay close attention to startup-stage regression testing.
- `@Inject`, `@Overwrite`, and `@Redirect` all depend on accurate method names and JVM descriptors. Target method signature changes will make injection fail.
- When multiple mods modify the same target method, use `ISeraMixin#getPriority()` and narrower injection points to reduce conflicts.
- When using SeraMixin, SeraphinaLib must be placed in `mods/` as a top-level mod jar. Consuming mods should not JarJar-embed the same library, otherwise duplicate modules or duplicate packages may be produced.
- Client image utilities depend on Minecraft client classes and should only be used in client-side rendering code.

## License

This project is released under the [MIT License](LICENSE).

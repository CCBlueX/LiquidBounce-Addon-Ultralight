# LiquidBounce Ultralight

A [LiquidBounce](https://github.com/CCBlueX/LiquidBounce) add-on that lets the client show its menus with
[Ultralight](https://ultralig.ht) instead of the built-in Chromium. It is only an alternative: LiquidBounce does not
run better or faster with it.

With the add-on installed, LiquidBounce asks which browser to use the next time it starts. The choice is remembered;
hold Shift while the client starts to choose again. `LB_BROWSER_BACKEND=ultralight` picks it without asking.

| Choosing | Ultralight |
|---|---|
| ![Choosing the browser](docs/selection.png) | ![The title screen in Ultralight](docs/title.png) |

Ultralight renders on the GPU through the game's own renderer, on OpenGL and Vulkan. Its SDK is downloaded from
Ultralight on the first start. The bindings are [Ultralight Java Reborn](https://github.com/CCBlueX/ultralight-java-reborn).

## Building

```
./gradlew build
```

`./gradlew runClientGameTest` starts the client with the add-on, picks Ultralight on the selection screen and takes
the screenshots above.

## License

The add-on is licensed under the GPL 3.0 or later, see [LICENSE](LICENSE). Its shaders are ported from Ultralight's
[AppCore](https://github.com/ultralight-ux/AppCore) (LGPL 2.1). Ultralight itself is © Ultralight, Inc. and used under
its license.

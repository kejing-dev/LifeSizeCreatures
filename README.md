# 真实比例动物 · Life-size Creatures

<img src="docs/icon.png" width="96" alt="App icon: a T. rex, a small person to the same scale and a yellow tape on jungle green">

![A horse at true size on the floor, with a 2.4 m tape, a figure of your height and a size tag](docs/screenshots/01-horse-side-by-side.jpg)

> 截图和演示视频都来自 PICO 6.1 模拟器，用调试命令驱动，**不是真实手势录像**，也没有在真机上验证过。
> Screenshots and the demo video come from the PICO 6.1 emulator, driven by debug commands. **They are not recordings of real hand input**, and the app has not been verified on a headset yet.

## 中文

把 5 米长的大白鲨放进客厅，跟自己比一比。

图鉴是共享空间里的一扇普通窗口，可以和别的应用并排放着。点一只动物，应用进入全空间，点一下地板，它就按真实大小站在你的地板上：

- 地上一条黄色卷尺，量出它有多长；
- 旁边站着一个和你一样高的「你」；
- 比房间还高时，天花板上画一圈虚线，告诉你「比你的房间还高 1.4 m」；也可以在信息卡里选「装进房间」缩小显示；
- 点动物，它转过来看你，弹出信息卡：中英名、学名、体长 / 高度 / 体重、栖息地、一句冷知识；
- 房间里最多两只，可以捏住拖动、转 90°、送回图鉴；
- 点「退出全空间」回到共享空间。

8 只动物，从小到大：柴犬 0.7 m、灰狼 1.4 m、伶盗龙 2.0 m、马鹿 2.0 m、马 2.4 m、大白鲨 4.6 m、三角龙 8.5 m、霸王龙 12.3 m。中英双语，完全离线，不要账号，不保存任何数据。

包名 `io.embodit.lifesize` · PICO Spatial SDK 6.1.9 · 界面全部用 SpatialUI + PicoTheme · 开发说明见 `AGENTS.md`，设计包见 `design/design-package.json`。

## English

Put a 5-metre great white shark in your living room and stand next to it.

The field guide is an ordinary window in the Shared Space, so it can sit next to other apps. Pick an animal and the app enters the Full Space; tap the floor and the animal stands there at its true size:

- a yellow tape on the floor reads its length;
- a figure of your own height stands beside it;
- when it is taller than your room, a dashed line marks your ceiling and the tag says how much taller; the info card can also shrink it to fit the room;
- tap the animal and it turns to look at you, with an info card: names in Chinese and English, Latin name, length / height / weight, habitat and one fun fact;
- up to two animals at once; pinch to drag, turn 90°, or send one back to the guide;
- "Exit Full Space" returns to the Shared Space.

Eight animals, smallest first: Shiba Inu 0.7 m, gray wolf 1.4 m, velociraptor 2.0 m, red deer 2.0 m, horse 2.4 m, great white shark 4.6 m, triceratops 8.5 m, Tyrannosaurus rex 12.3 m. Chinese and English, fully offline, no account, nothing stored.

Package `io.embodit.lifesize` · PICO Spatial SDK 6.1.9 · all 2D UI is SpatialUI + PicoTheme · developer notes in `AGENTS.md`, design package in `design/design-package.json`.

## 截图 · Screenshots

| | |
|---|---|
| ![](docs/screenshots/02-field-guide-shared-space.jpg)<br>图鉴窗在共享空间 · The field guide in the Shared Space | ![](docs/screenshots/03-shiba-info-card.jpg)<br>柴犬转身 + 信息卡 · The Shiba turns to you, with its info card |
| ![](docs/screenshots/04-shark-and-shiba.jpg)<br>大白鲨和柴犬同框 · A great white shark and a Shiba Inu | ![](docs/screenshots/05-trex-bigger-than-room.jpg)<br>霸王龙比房间还大 · The T. rex is bigger than the room |
| ![](docs/screenshots/06-raptor-and-stag.jpg)<br>伶盗龙和马鹿 · A velociraptor and a red deer | ![](docs/screenshots/07-english.jpg)<br>英文界面 · English UI |

演示视频 Demo video: [`docs/LifeSizeCreatures-demo.mp4`](docs/LifeSizeCreatures-demo.mp4)（53 s，1080 × 1080，无声 / no sound；模拟器 + 调试命令，非真实手势录像 / emulator + debug commands, not real hand input）。

## 安装与启动 · Install and launch

从 Release 下载 APK（debug 签名），然后：
Download the APK (debug-signed) from the Release, then:

```bash
adb install -r LifeSizeCreatures-v0.1.0.apk
pico-cli app launch io.embodit.lifesize
```

从源码构建 Build from source: `tools/dev.sh build`（需要 JDK 21 和 Android SDK / needs JDK 21 and the Android SDK）。

## 模型与许可 · Credits

8 个 3D 模型全部是 **CC BY 4.0**（https://creativecommons.org/licenses/by/4.0/），通过 Objaverse 镜像取自 Sketchfab，许可已对 Sketchfab API 核对。**有修改：** 狼、马、霸王龙的材质从 spec-gloss 转为 metal-rough；所有模型在应用里按真实尺寸缩放并调整朝向。每只动物的署名也显示在应用内信息卡底部。

All eight 3D models are **CC BY 4.0** (https://creativecommons.org/licenses/by/4.0/), obtained through the Objaverse mirror of Sketchfab, with the license checked on the Sketchfab API. **Modified:** materials converted from spec-gloss to metal-rough where needed (wolf, horse, T. rex); every model is scaled to true size and re-oriented in the app. Each credit is also shown at the foot of the animal's info card.

| 动物 Animal | 模型 Model | 作者 Author | 许可 License | 来源 Source |
|---|---|---|---|---|
| 柴犬 Shiba Inu | "Animated Dog, Shiba Inu" | quander | CC BY 4.0 | https://sketchfab.com/3d-models/9abfce885a834399b2c3ccaed51cd474 |
| 灰狼 Gray wolf | "Gray Wolf" | hamidkhan224 | CC BY 4.0 | https://sketchfab.com/3d-models/a2832b845e4e4edd9d439342cf4fd590 |
| 伶盗龙 Velociraptor | "Velociraptor" | Tomas Ibar | CC BY 4.0 | https://sketchfab.com/3d-models/b227da6c817043ab9a249dbbd127db67 |
| 马鹿 Red deer | "Deer" | aeonfitzgerald | CC BY 4.0 | https://sketchfab.com/3d-models/01f33919650c421e91666908db8c421e |
| 马 Horse | "The horse" | Carlos Villalba | CC BY 4.0 | https://sketchfab.com/3d-models/1483a55c785d4379a25441681153cfce |
| 大白鲨 Great white shark | "White Pointer" | VFX Stevenz | CC BY 4.0 | https://sketchfab.com/3d-models/8e429052939a4677861d0d550a0e27cd |
| 三角龙 Triceratops | "Triceratops dinosaur" | wojciechmiedziocha | CC BY 4.0 | https://sketchfab.com/3d-models/87527079bad44917ab1b98a456b46c7e |
| 霸王龙 T. rex | "Randaling T- Rex, animated!" | quander | CC BY 4.0 | https://sketchfab.com/3d-models/09a3632365344a99bb88e3dd0b684c89 |

字体 Fonts: Noto Sans and Noto Sans SC (subset), SIL Open Font License 1.1 (`third_party/`).

模型的已知局限 Known model caveats: 马鹿是通用雄鹿模型，不专门是马鹿；伶盗龙是无羽毛的「电影版」；按真实体长缩放后伶盗龙比名录高 39 %、霸王龙高约 30 %，有更好的 CC0 / CC BY 模型时替换。The deer is a generic antlered stag, not specifically a red deer; the velociraptor is a featherless "movie" raptor; scaled to the true length, the raptor (+39 %) and the T. rex (about +30 %) stand taller than the roster's top height — both are flagged for replacement when better CC0 / CC BY models are found.

## 还没在真机上验证 · Not yet verified on a headset

真实房间里的尺度观感、捏取拖动手感、「你」的身高估计、地板 / 天花板识别、空间数据授权、写实模型的加载时间与帧率、半透明面板、叫声——这些只在模拟器上跑过通路，真机待验。Scale in a real room, pinch-and-drag feel, the height estimate, real floor / ceiling detection, the spatial-data permission prompt, model load time and frame rate, translucent panels and the animal call have only been exercised on the emulator.

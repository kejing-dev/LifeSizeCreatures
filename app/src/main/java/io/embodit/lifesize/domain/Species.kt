package io.embodit.lifesize.domain

/** Body length measured to the tail root (mammals) or to the tail tip (dinosaurs, shark). */
enum class LengthKind { HEAD_BODY, TOTAL }

/** Which height the size tag names. */
enum class HeightKind { SHOULDER, HIP, BODY }

/**
 * One animal of the roster (design-package `roster`, sorted by length). Figures are common adult values
 * from public encyclopaedias (Wikipedia / Britannica), within the design's +-10 %.
 */
data class Species(
    val id: String,
    val zh: String,
    val en: String,
    val latin: String,
    val lengthM: Float,
    val lengthKind: LengthKind,
    val heightM: Float,
    val heightKind: HeightKind,
    val massKg: String,
    val habitatZh: String,
    val habitatEn: String,
    val factZh: String,
    val factEn: String,
    /** Belly above the floor while shown (the shark swims); 0 for animals standing on the floor. */
    val floatM: Float = 0f,
    val model: ModelSpec,
) {
    fun name(lang: Lang) = if (lang == Lang.ZH) zh else en

    fun habitat(lang: Lang) = if (lang == Lang.ZH) habitatZh else habitatEn

    fun fact(lang: Lang) = if (lang == Lang.ZH) factZh else factEn

    /** Uniform model scale at 1:1 (design: scale = lengthM / lengthRefM, never non-uniform). */
    val scale: Float
        get() = lengthM / model.lengthRef

    /**
     * Size of the model at 1:1 in metres: x = length axis (snout +x), y = up, z = across. The rendered size
     * measured in the room wins over the file box (skinned files pose differently in the engine).
     */
    val sizeM: Vec3
        get() = ModelMetrics.sizes[id] ?: Vec3((model.maxZ - model.minZ) * scale, (model.maxY - model.minY) * scale, (model.maxX - model.minX) * scale)

    /** Top of the box above the floor at 1:1 (the ceiling check uses this, floating included). */
    val topM: Float
        get() = floatM + sizeM.y

    /** Snout to the far end of the box (tail tip) along the length axis, metres. */
    val fullLengthM: Float
        get() = sizeM.x

    val widthM: Float
        get() = sizeM.z
}

/**
 * Where the model sits in its own file: the default-pose box in file units after the node transforms
 * (tools/prep_models.py, tools/models.json). Every file is Y-up with the head towards +Z. [lengthRef]
 * is snout (maxZ) to the end of the measured length: the tail root for mammals (read off the height
 * profile), the whole box for the others.
 */
data class ModelSpec(
    val asset: String,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
    val lengthRef: Float,
    /** Clip names: looped while standing, played once when the animal is tapped; null = none. */
    val idleClip: String? = null,
    val reactClip: String? = null,
    /** CC BY credit line (README "Credits", info card footer). */
    val credit: String,
    /**
     * Extra turn about +X (degrees) for a file whose skinned pose the engine stands upright (the shark:
     * the engine poses its armature with the length along +Y). Found on the emulator, see AGENTS.md.
     */
    val fixPitchDeg: Float = 0f,
)

/**
 * Sizes measured on the rendered model (scene calibration, true size, metres). Set on the main thread
 * when a model first stands in the room; read by the room rules (fit, footprint, tag height).
 */
object ModelMetrics {
    val sizes = java.util.concurrent.ConcurrentHashMap<String, Vec3>()
}

object Roster {
    val all: List<Species> =
        listOf(
            Species(
                id = "shiba", zh = "柴犬", en = "Shiba Inu", latin = "Canis familiaris",
                lengthM = 0.7f, lengthKind = LengthKind.HEAD_BODY, heightM = 0.4f, heightKind = HeightKind.SHOULDER, massKg = "8–10",
                habitatZh = "家犬，原产日本", habitatEn = "Domestic dog, from Japan",
                factZh = "体重和一个 1 岁小孩差不多。", factEn = "About as heavy as a 1-year-old child.",
                model = ModelSpec("models/shiba.glb", -16.3196f, 28.9273f, -0.038f, 85.5199f, -38.3545f, 58.767f, 90.323f,
                    idleClip = "0|standing_0", reactClip = "0|shake_0", credit = "\"Animated Dog, Shiba Inu\" by quander, CC BY 4.0"),
            ),
            Species(
                id = "wolf", zh = "灰狼", en = "Gray Wolf", latin = "Canis lupus",
                lengthM = 1.4f, lengthKind = LengthKind.HEAD_BODY, heightM = 0.8f, heightKind = HeightKind.SHOULDER, massKg = "30–50",
                habitatZh = "北美、欧亚的森林与草原", habitatEn = "Forests and grasslands of North America and Eurasia",
                factZh = "用后腿站起来，和大人差不多高。", factEn = "Standing on its hind legs, it is about as tall as an adult.",
                model = ModelSpec("models/wolf.glb", -0.0356f, 0.0445f, -0.0626f, 0.0435f, 0.6784f, 0.8388f, 0.1363f,
                    credit = "\"Gray Wolf\" by hamidkhan224, CC BY 4.0"),
            ),
            Species(
                id = "velociraptor", zh = "伶盗龙（迅猛龙）", en = "Velociraptor", latin = "Velociraptor mongoliensis",
                lengthM = 2.0f, lengthKind = LengthKind.TOTAL, heightM = 0.5f, heightKind = HeightKind.HIP, massKg = "≈15",
                habitatZh = "白垩纪晚期，蒙古戈壁", habitatEn = "Late Cretaceous, Gobi (Mongolia)",
                factZh = "真实的它只到你膝盖，比电影里小得多。", factEn = "The real one only reaches your knee — far smaller than in the movies.",
                model = ModelSpec("models/velociraptor.glb", -0.4198f, 0.4198f, -0.2426f, 1.4885f, -2.5325f, 0.7821f, 3.3146f,
                    credit = "\"Velociraptor\" by Tomas Ibar, CC BY 4.0"),
            ),
            Species(
                id = "stag", zh = "马鹿（公）", en = "Red Deer (stag)", latin = "Cervus elaphus",
                lengthM = 2.0f, lengthKind = LengthKind.HEAD_BODY, heightM = 1.2f, heightKind = HeightKind.SHOULDER, massKg = "160–240",
                habitatZh = "欧洲、亚洲的林地", habitatEn = "Woodlands of Europe and Asia",
                factZh = "鹿角每年脱落再长，一季能长近 1 米。", factEn = "Its antlers fall off and regrow every year — up to about 1 m in a season.",
                model = ModelSpec("models/stag.glb", -2.3141f, 2.3179f, -0.1837f, 10.3782f, -5.434f, 3.5988f, 8.8521f,
                    credit = "\"Deer\" by aeonfitzgerald, CC BY 4.0"),
            ),
            Species(
                id = "horse", zh = "马", en = "Horse", latin = "Equus caballus",
                lengthM = 2.4f, lengthKind = LengthKind.HEAD_BODY, heightM = 1.6f, heightKind = HeightKind.SHOULDER, massKg = "380–1000",
                habitatZh = "全世界驯养", habitatEn = "Domesticated worldwide",
                factZh = "马站着也能睡觉。", factEn = "Horses can sleep standing up.",
                model = ModelSpec("models/horse.glb", -2.3575f, 3.1764f, -8.2697f, 8.2063f, -10.0062f, 10.4211f, 15.9333f,
                    credit = "\"The horse\" by Carlos Villalba, CC BY 4.0"),
            ),
            Species(
                id = "shark", zh = "大白鲨", en = "Great White Shark", latin = "Carcharodon carcharias",
                lengthM = 4.6f, lengthKind = LengthKind.TOTAL, heightM = 1.5f, heightKind = HeightKind.BODY, massKg = "680–1100",
                habitatZh = "全球温带近海", habitatEn = "Temperate coastal seas worldwide",
                factZh = "一生会换掉上万颗牙。", factEn = "It sheds tens of thousands of teeth in a lifetime.",
                floatM = 0.4f,
                model = ModelSpec("models/shark.glb", -68.4023f, 54.1363f, -57.2606f, 64.4587f, -132.2018f, 142.3303f, 274.5321f,
                    idleClip = "Swimming01.001", credit = "\"White Pointer\" by VFX Stevenz, CC BY 4.0", fixPitchDeg = 90f),
            ),
            Species(
                id = "triceratops", zh = "三角龙", en = "Triceratops", latin = "Triceratops horridus",
                lengthM = 8.5f, lengthKind = LengthKind.TOTAL, heightM = 3.0f, heightKind = HeightKind.HIP, massKg = "6000–12000",
                habitatZh = "白垩纪晚期，北美西部", habitatEn = "Late Cretaceous, western North America",
                factZh = "光头骨就有 2 米多长。", factEn = "Its skull alone is over 2 m long.",
                model = ModelSpec("models/triceratops.glb", -0.7555f, 0.7555f, -0.0018f, 1.8215f, -2.5152f, 2.0705f, 4.5857f,
                    credit = "\"Triceratops dinosaur\" by wojciechmiedziocha, CC BY 4.0"),
            ),
            Species(
                id = "trex", zh = "霸王龙", en = "Tyrannosaurus rex", latin = "Tyrannosaurus rex",
                lengthM = 12.3f, lengthKind = LengthKind.TOTAL, heightM = 3.9f, heightKind = HeightKind.HIP, massKg = "≈8400",
                habitatZh = "白垩纪晚期，北美西部", habitatEn = "Late Cretaceous, western North America",
                factZh = "一颗牙连牙根能有一根香蕉那么长。", factEn = "One tooth, root included, can be as long as a banana.",
                model = ModelSpec("models/trex.glb", -1.5458f, 1.5458f, -0.0201f, 5.6306f, -6.5466f, 5.6468f, 12.1934f,
                    idleClip = "idle", reactClip = "roar", credit = "\"Randaling T- Rex, animated!\" by quander, CC BY 4.0"),
            ),
        )

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Species? = byId[id]
}

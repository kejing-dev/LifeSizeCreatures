package io.embodit.lifesize.domain

import java.util.Locale

/**
 * Every word the app shows, in both languages (design `bilingual`: the guide's [中 | EN] switches all of
 * it at runtime, independent of the system locale, so the copy lives here and not in resources).
 * tools/subset_cjk_font.py reads this file and Species.kt to build the Chinese font subset.
 */
class Copy(val lang: Lang) {
    private fun t(zh: String, en: String) = if (lang == Lang.ZH) zh else en

    val title = t("真实比例动物", "Life-size Creatures")
    val exitFull = t("退出全空间", "Exit Full Space")
    val retry = t("重试", "Retry")
    val clearAll = t("清空", "Clear all")
    val langZh = "中"
    val langEn = "EN"

    // ---- hint line (stateModel.hintPriority)
    val hintShared = t("点一只动物，放进你的房间", "Pick an animal to bring into your room")
    val hintEntering = t("正在进入全空间…", "Entering Full Space…")
    val hintEnterFailed = t("没能进入全空间，请再试一次", "Couldn't enter Full Space. Please try again.")
    val hintFinding = t("环顾一下房间，正在找地板…", "Look around the room — finding the floor…")
    val hintNoFloor = t("没找到地板。请先在系统设置里画好房间边界，再点重试", "No floor found. Set up your room boundary in Settings, then tap Retry.")
    val hintLoading = t("正在把它带进来…", "Bringing it in…")
    val hintLoadFailed = t("这只没带来，请再试一次", "Couldn't bring this one in. Please try again.")
    val hintPlacing = t("点一下地板放下 · 再点一次取消", "Tap the floor to place it · tap again to cancel")
    val hintDragging = t("松手放下", "Let go to put it down")
    val hintScaled = t("已缩小显示 · 数字仍是真实大小", "Shown smaller · the numbers are still true size")
    val hintTooBig = t("它比你的房间还大！点它可以缩小装进来", "It's bigger than your room! Tap it to shrink it to fit")
    val hintTwo = t("最多两只 · 再放会替换先放的那只", "Two at most · a third one replaces the first")
    val hintOne = t("点动物看看它 · 捏住可拖动", "Tap an animal to meet it · pinch and drag to move it")
    val hintEmpty = t("选一只动物", "Pick an animal")

    fun hintReplaced(name: String) = t("先放的${name}回图鉴了", "The $name went back to the guide")

    // ---- size tag, card, labels
    fun lengthAndHeight(s: Species) = t("长 ${m(s.lengthM)} · ${heightWord(s)} ${m(s.heightM)}", "Length ${m(s.lengthM)} · ${heightWord(s)} ${m(s.heightM)}")

    fun heightWord(s: Species) =
        when (s.heightKind) {
            HeightKind.SHOULDER -> t("肩高", "shoulder")
            HeightKind.HIP -> t("髋高", "hip")
            HeightKind.BODY -> t("身高", "body")
        }

    fun tallerThanRoom(over: Float) = t("比你的房间还高 ${m(over)}", "${m(over)} taller than your room")

    fun longerThanRoom(over: Float) = t("比你的房间还长 ${m(over)}", "${m(over)} longer than your room")

    fun scaledBadge(n: Int) = t("1:$n 缩小显示", "Shown at 1:$n")

    fun you(heightM: Float) = t("你 ${m(heightM)}", "You ${m(heightM)}")

    val rowLength = t("体长", "Length")

    fun lengthNote(s: Species) =
        when (s.lengthKind) {
            LengthKind.HEAD_BODY -> t("头体长，不含尾", "head–body, without tail")
            LengthKind.TOTAL -> t("全长", "total")
        }

    val rowHeight = t("高度", "Height")
    val rowMass = t("体重", "Weight")
    val trueSize = t("真实 1:1", "True 1:1")

    fun fitRoom(n: Int) = t("装进房间 1:$n", "Fit room 1:$n")

    val rotate = t("转 90°", "Turn 90°")
    val sendBack = t("送回图鉴", "Send back")
    val close = t("关闭", "Close")

    companion object {
        /** Metres with one decimal ("4.6 m"), the same in both languages. */
        fun m(v: Float): String = String.format(Locale.US, "%.1f m", v)

        fun kg(s: String): String = "$s kg"
    }
}

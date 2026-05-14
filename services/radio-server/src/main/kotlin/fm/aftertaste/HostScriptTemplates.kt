package fm.aftertaste

import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * Deterministic host-script copy for the no-LLM-key path (`ShowPlanner`). The LLM planner writes its
 * own scripts; this is the fallback. Copy is templated per host language: English and Mandarin are
 * separate template sets, not one set translated, because radio cadence does not translate one-to-one.
 */
object HostScriptTemplates {
    fun generate(
        segmentTitle: String,
        tracks: List<Track>,
        context: RecommendationContext,
        chapterIndex: Int,
        hostLanguage: String
    ): String =
        if (isChineseHostLanguage(hostLanguage)) {
            chineseScript(segmentTitle, tracks, context, chapterIndex)
        } else {
            englishScript(segmentTitle, tracks, context, chapterIndex)
        }

    private fun englishScript(
        segmentTitle: String,
        tracks: List<Track>,
        context: RecommendationContext,
        chapterIndex: Int
    ): String {
        val lead = tracks.firstOrNull()
        val clock = OffsetDateTime.now()
        val timeText = "%d:%02d".format(clock.hour, clock.minute)
        val mood = englishMoodLabel(context)
        val station = context.stationStyle?.label?.lowercase() ?: "the station"
        val leadLine = lead?.let { "Now, ${it.artist}, ${it.title}." } ?: "Let this chapter take its time."
        val leadIntro = lead?.let { "${it.title} by ${it.artist}" } ?: "this next record"
        val weather = context.weather?.let {
            " Outside in ${friendlyPlaceName(it.locationName)}, it is ${it.condition} and ${"%.0f".format(it.temperatureC)} degrees."
        }.orEmpty()
        val seed = listOf(segmentTitle, context.variationSeed, lead?.id).joinToString("|").hashCode()
        val firstChapterOpenings = listOf(
            "It is $timeText now.$weather We start quietly, with $leadIntro close enough to feel personal and far enough away to leave some room.",
            "$timeText.$weather This first chapter does not need a grand entrance. $leadIntro can come in at the pace of $station.",
            "The hour is $timeText.$weather For the first record, we keep it close and let $leadIntro set the pace without announcing itself too hard.",
            "${weather.trimStart()} The clock says $timeText, and this opening chapter is more about settling than declaring. $leadIntro gives us that first soft edge."
        )
        return when (chapterIndex) {
            0 -> "${firstChapterOpenings.randomBy(seed)} It keeps close to $mood without forcing the feeling into shape. $leadLine"
            1 -> listOf(
                "A few songs in, the station has changed texture. This chapter stays with what remains after the noise falls away. $leadIntro gives that feeling a center, then the next songs get to move cleanly around it. $leadLine",
                "We turn a little, but we do not break the spell. $leadIntro keeps things close and steady, the kind of song that lets memory be present without making a speech out of it. $leadLine",
                "The second chapter should feel less like a reset and more like a handoff. $leadIntro carries the thread forward, soft at the edges and clear in the middle. $leadLine"
            ).randomBy(seed)
            2 -> listOf(
                "This is where the room gets a little wider. $leadIntro gives the chapter more air while keeping the pulse of $station intact. $leadLine",
                "Now we let the show breathe out. $leadIntro opens a wider lane, still careful, still close, but no longer holding every thought in place. $leadLine",
                "The middle has done its quiet work, so this chapter can lift without rushing. $leadIntro is the door opening a little farther. $leadLine"
            ).randomBy(seed)
            else -> listOf(
                "For the last chapter, we do not need to explain too much. The point is to leave the hour somewhere softer than where it began, and $leadIntro feels right for that. $leadLine",
                "We take the final turn without tying a ribbon around it. $leadIntro can carry us out slowly, with enough distance to feel calm and enough warmth to stay near. $leadLine",
                "This last stretch is for letting the room settle. $leadIntro does not demand an answer; it just gives the ending somewhere gentle to land. $leadLine"
            ).randomBy(seed)
        }
    }

    private fun chineseScript(
        segmentTitle: String,
        tracks: List<Track>,
        context: RecommendationContext,
        chapterIndex: Int
    ): String {
        val lead = tracks.firstOrNull()
        val clock = OffsetDateTime.now()
        val timeText = "%d:%02d".format(clock.hour, clock.minute)
        val mood = chineseMoodLabel(context)
        // Keep titles/artists in their original language; do not translate them.
        val leadLine = lead?.let { "接下来，${it.artist}，《${it.title}》。" } ?: "这一章，就让它慢慢来。"
        val leadIntro = lead?.let { "${it.artist} 的《${it.title}》" } ?: "下面这首歌"
        val weather = context.weather?.let {
            " 窗外的${friendlyPlaceName(it.locationName)}，${"%.0f".format(it.temperatureC)} 度。"
        }.orEmpty()
        val seed = listOf(segmentTitle, context.variationSeed, lead?.id).joinToString("|").hashCode()
        // Short sentences on purpose: more 。breaks give Fish clean pause points, so the
        // Mandarin host lands like someone talking, not a machine reading a paragraph.
        return when (chapterIndex) {
            0 -> listOf(
                "现在是 $timeText。$weather 我们慢慢来。先放 $leadIntro。让它离得够近，又留一点呼吸的空间。",
                "$timeText。$weather 第一章，不用什么隆重的开场。$leadIntro 就这样，不动声色地进来。",
                "时间走到 $timeText。$weather 第一首歌，把声音放低一点。让 $leadIntro 自己定节奏。"
            ).randomBy(seed) + " 它贴着$mood。但不会把那种感觉，硬掰成形状。$leadLine"
            1 -> listOf(
                "几首歌过去了。房间的质地，变了。这一章留下的，是喧嚣退去之后还在的东西。$leadIntro 给了它一个中心。$leadLine",
                "我们转了个弯。但没有打破这份安静。$leadIntro 把一切稳稳托住。让回忆在场，又不必大声说话。$leadLine",
                "第二章，更像一次交接，而不是重来。$leadIntro 把那根线，接着往下带。$leadLine"
            ).randomBy(seed)
            2 -> listOf(
                "到这里，房间稍微宽了一点。$leadIntro 让这一章多了些空气。原来的脉搏，还在。$leadLine",
                "现在，让节目呼出一口气。$leadIntro 打开一条更宽的路。还是小心，还是贴近。$leadLine",
                "中段，已经做完了它安静的工作。这一章，可以不慌不忙地抬起来。$leadIntro 就是那扇门，再开大一点。$leadLine"
            ).randomBy(seed)
            else -> listOf(
                "最后一章了。不用解释太多。把这一个小时，留在比开始时更柔软的地方，就好。$leadIntro 刚好合适。$leadLine",
                "我们走最后一个转弯。不用给它系上蝴蝶结。$leadIntro 慢慢把我们带出去。$leadLine",
                "这最后一段，留给房间慢慢沉下来。$leadIntro 不要一个答案。只给结尾，一个温柔的落点。$leadLine"
            ).randomBy(seed)
        }
    }

    private fun englishMoodLabel(context: RecommendationContext): String {
        val routing = context.routing
        return when {
            "too-sad" in routing.avoid -> "something soft without letting the room get too heavy"
            routing.language?.startsWith("zh") == true && routing.energy == "low" -> "a low-energy Chinese indie thread"
            routing.routine == "late-night-coding" -> "late-night focus with the edges softened"
            routing.energy == "low" -> "a quiet, low-energy stretch"
            context.mood.isNullOrBlank() -> context.stationStyle?.hostStyle ?: "that quiet feeling where memory is present, but not loud"
            else -> "the feeling you asked for"
        }
    }

    private fun chineseMoodLabel(context: RecommendationContext): String {
        val routing = context.routing
        return when {
            "too-sad" in routing.avoid -> "柔和一点的感觉，又不让整个房间太沉"
            routing.language?.startsWith("zh") == true && routing.energy == "low" -> "一段慢下来的华语独立音乐"
            routing.routine == "late-night-coding" -> "深夜写代码时那种、把边角磨软的专注"
            routing.energy == "low" -> "安静、低能量的一段"
            context.mood.isNullOrBlank() -> "那种记忆还在、却不喧哗的感觉"
            else -> "你想要的那种感觉"
        }
    }
}

internal fun <T> List<T>.randomBy(seed: Int): T = this[Random(seed).nextInt(size)]

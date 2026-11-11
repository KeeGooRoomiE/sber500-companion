package ru.keegoo.companion.ui.home

/**
 * «День 0» (AND-02): what a newcomer is told in place of blocks that have nothing to show yet.
 *
 * Cold traffic lands on a Home full of buttons that lead nowhere: no check-in yet, so no review;
 * no watch, so no sleep or steps. These lines say so plainly instead of leaving empty tiles.
 * Observer tone throughout: statements, no advice.
 */
object Day0Copy {
    const val NO_HEALTH = "Шаги и сон я не вижу: на телефоне нет источника, который их записывает. " +
        "Прогноз строится по экрану и разблокировкам."
    const val NO_REVIEW = "Разбор появится после первой вечерней отметки: мне нужно знать, каким день считаешь ты сам."
    const val CHECKIN_VALUE = "Это единственное, чего телефон не знает сам. В ответ покажу разбор дня."
    const val ACQUAINTANCE = "Прогноз пока опирается на общие закономерности. " +
        "После нескольких твоих отметок будет опираться на твои."
}

data class Day0(
    /** No watch, band or health app writes sleep and steps: the forecast runs on the screen alone. */
    val noHealthSource: Boolean = false,
    /** Not a single evening mark yet: a review has nothing to explain. */
    val noCheckInsYet: Boolean = false,
    /** Too few marks for the forecast to be personal. */
    val acquaintance: Boolean = false,
)

const val ACQUAINTANCE_CHECKINS = 3

/**
 * [checkIns] is counted on this phone and starts at zero for people who updated from a build
 * that did not count. [pastForecasts] (the history from the server) tells those people apart
 * from real newcomers: someone with a week of forecasts behind them is not on day 0, whatever
 * the fresh counter says.
 */
fun day0(checkIns: Int, pastForecasts: Int, noHealthSource: Boolean) = Day0(
    noHealthSource = noHealthSource,
    noCheckInsYet = checkIns == 0 && pastForecasts <= 1,
    acquaintance = checkIns < ACQUAINTANCE_CHECKINS && pastForecasts < 5,
)

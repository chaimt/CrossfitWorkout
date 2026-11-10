package com.wodcapture.domain

import com.wodcapture.domain.model.AbsoluteLoad
import com.wodcapture.domain.model.AggregationOp
import com.wodcapture.domain.model.Amrap
import com.wodcapture.domain.model.AthleteChoiceLoad
import com.wodcapture.domain.model.AthleteProfile
import com.wodcapture.domain.model.Emom
import com.wodcapture.domain.model.FallbackTimer
import com.wodcapture.domain.model.ForTime
import com.wodcapture.domain.model.Group
import com.wodcapture.domain.model.GroupKind
import com.wodcapture.domain.model.InnerMode
import com.wodcapture.domain.model.Intervals
import com.wodcapture.domain.model.Item
import com.wodcapture.domain.model.ItemRole
import com.wodcapture.domain.model.Length
import com.wodcapture.domain.model.LengthUnit
import com.wodcapture.domain.model.Level
import com.wodcapture.domain.model.Mass
import com.wodcapture.domain.model.MassUnit
import com.wodcapture.domain.model.Measure
import com.wodcapture.domain.model.MeasureKind
import com.wodcapture.domain.model.DistanceUnit
import com.wodcapture.domain.model.Override
import com.wodcapture.domain.model.Part
import com.wodcapture.domain.model.PartHints
import com.wodcapture.domain.model.PartKind
import com.wodcapture.domain.model.PartnerHint
import com.wodcapture.domain.model.PercentOfBodyweight
import com.wodcapture.domain.model.PercentOfMax
import com.wodcapture.domain.model.Progression
import com.wodcapture.domain.model.RepTarget
import com.wodcapture.domain.model.Rest
import com.wodcapture.domain.model.ScoreType
import com.wodcapture.domain.model.Scoring
import com.wodcapture.domain.model.Side
import com.wodcapture.domain.model.Source
import com.wodcapture.domain.model.SourceKind
import com.wodcapture.domain.model.Station
import com.wodcapture.domain.model.StrengthSet
import com.wodcapture.domain.model.StrengthSets
import com.wodcapture.domain.model.Workout
import com.wodcapture.domain.plan.PlanIdentity
import com.wodcapture.domain.review.ReviewActions
import java.io.File

/** One Appendix H contract fixture: expected parse, review decisions and compile inputs. */
data class HFixture(
    val number: Int,
    val expected: Workout,
    val review: (Workout) -> Workout,
    val profile: AthleteProfile = TestSupport.profile,
) {
    val name = "H$number"
    val identity = PlanIdentity(fid(number, 4, 1), 1)
    fun input(): String = File(TestSupport.sharedDir, "fixtures/$name/input.txt").readText()
}

/**
 * Hand-authored expected parses of Appendix H (REQ-AX12, REQ-TS08). IDs are deterministic
 * (`fid`), loads are stored as written, board pairs kept as notes, fallbacks not yet accepted —
 * exactly what a correct parser should return before review.
 */
object Fixtures {
    private const val DATE = "2026-11-03"
    private const val TZ = "Asia/Jerusalem"
    private fun lb(v: Double) = Mass(v, MassUnit.LB)
    private fun kg(v: Double) = Mass(v, MassUnit.KG)
    private fun reps(n: Int) = Measure(MeasureKind.REPS, n.toDouble())
    private val scheme = Measure(MeasureKind.REP_SCHEME)

    private fun workout(n: Int, title: String, vararg parts: Part) = Workout(
        id = fid(n, 0, 1), title = title, date = DATE, scheduleTimeZone = TZ,
        source = Source(SourceKind.TEXT, parser = "fixture"), parts = parts.toList(),
    )

    private fun node(f: Int, n: Int) = fid(f, 2, n)
    private fun set(f: Int, n: Int) = fid(f, 3, n)

    val h1 = HFixture(
        1,
        workout(
            1, "Fran",
            Part(
                id = fid(1, 1, 1), label = "WOD", kind = PartKind.METCON, title = "Fran", format = ForTime(capSec = 480),
                body = listOf(
                    Group(
                        node(1, 1), GroupKind.ROUNDS, repeat = 3, repScheme = listOf(21, 15, 9),
                        children = listOf(
                            Item(node(1, 2), "thruster", "Thruster", measure = scheme, load = AbsoluteLoad(lb(95.0)), notes = "Board: 95/65 lb", implement = "barbell"),
                            Item(node(1, 3), "pull_up", "Pull-up", measure = scheme),
                        ),
                    ),
                ),
                scoring = Scoring(ScoreType.TIME),
                levels = listOf(
                    Level(
                        "Scaled",
                        listOf(
                            Override(node(1, 2), load = AbsoluteLoad(lb(65.0))),
                            Override(node(1, 3), movementId = "jumping_pull_up", name = "Jumping pull-up"),
                        ),
                    ),
                ),
            ),
        ),
        review = { it },
    )

    val h2 = HFixture(
        2,
        workout(
            2, "Squat + E2MOM",
            Part(
                id = fid(2, 1, 1), label = "A", kind = PartKind.STRENGTH, format = StrengthSets(),
                body = listOf(
                    Item(
                        node(2, 1), "back_squat", "Back squat", implement = "barbell",
                        sets = (1..5).map { StrengthSet(set(2, it), RepTarget(3), PercentOfMax(80.0), restSec = 120) },
                    ),
                ),
                scoring = Scoring(ScoreType.LOAD),
            ),
            Part(
                id = fid(2, 1, 2), label = "B", kind = PartKind.STRENGTH, format = Emom(120, 6),
                body = listOf(
                    Group(
                        node(2, 2), GroupKind.COMPLEX,
                        children = listOf(
                            Item(node(2, 3), "power_clean", "Power clean", measure = reps(3), load = AthleteChoiceLoad("build"), implement = "barbell"),
                            Item(node(2, 4), "push_jerk", "Push jerk", measure = reps(1)),
                        ),
                    ),
                ),
                scoring = Scoring(ScoreType.LOAD),
            ),
        ),
        review = { w -> ReviewActions.acceptFallback(w, fid(2, 1, 2), FallbackTimer.COUNT_DOWN) },
    )

    val h3 = HFixture(
        3,
        workout(
            3, "EMOM 16",
            Part(
                id = fid(3, 1, 1), kind = PartKind.METCON,
                format = Emom(60, 16, stations = listOf(Station(node(3, 1)), Station(node(3, 2)), Station(node(3, 3), workSec = 40), Station(null))),
                body = listOf(
                    Item(node(3, 1), "row", "Row", measure = Measure(MeasureKind.CALORIES, 15.0), notes = "Board: 15/12 cal"),
                    Item(node(3, 2), "toes_to_bar", "Toes-to-bar", measure = reps(12)),
                    Item(node(3, 3), "double_under", "Double-under", measure = Measure(MeasureKind.MAX)),
                ),
                scoring = Scoring(ScoreType.COMPLETION, manual = true),
            ),
        ),
        review = { it },
    )

    val h4 = HFixture(
        4,
        workout(
            4, "AMRAP 15",
            Part(
                id = fid(4, 1, 1), kind = PartKind.METCON, format = Amrap(900),
                body = listOf(
                    Item(node(4, 1), "lateral_burpee_over_the_dumbbell", "Lateral burpee over DB", measure = reps(3), progression = Progression(3)),
                    Item(
                        node(4, 2), "dumbbell_hang_clean_to_overhead", "DB hang clean-to-overhead", measure = reps(3),
                        load = AbsoluteLoad(lb(50.0)), notes = "Board: 50/35 lb", implement = "dumbbell", progression = Progression(3),
                    ),
                    Item(node(4, 3), "walking_lunge", "Walking lunge", measure = Measure(MeasureKind.DISTANCE, 30.0, unit = DistanceUnit.FT)),
                ),
                scoring = Scoring(ScoreType.REPS, manual = true),
            ),
        ),
        review = { it },
    )

    val h5 = HFixture(
        5,
        workout(
            5, "3 rounds each for time",
            Part(
                id = fid(5, 1, 1), kind = PartKind.METCON, format = ForTime(eachRoundForTime = true),
                body = listOf(
                    Group(
                        node(5, 1), GroupKind.ROUNDS, repeat = 3,
                        children = listOf(
                            Item(node(5, 2), "run", "Run", measure = Measure(MeasureKind.DISTANCE, 400.0, unit = DistanceUnit.M)),
                            Item(node(5, 3), "american_kettlebell_swing", "Kettlebell swing", measure = reps(21), load = AbsoluteLoad(kg(24.0)), notes = "Board: 24/16 kg", implement = "kettlebell"),
                            Item(node(5, 4), "chest_to_bar_pull_up", "Chest-to-bar pull-up", measure = reps(12)),
                            Rest(node(5, 5), 180, inClock = false, betweenOnly = true),
                        ),
                    ),
                    Item(node(5, 6), "ghd_sit_up", "GHD sit-up", measure = reps(50), role = ItemRole.CASH_OUT),
                ),
                scoring = Scoring(ScoreType.TIME, aggregation = AggregationOp.EACH),
            ),
        ),
        review = { w -> ReviewActions.acceptRequiredFallbacks(w) },
    )

    val h6 = HFixture(
        6,
        workout(
            6, "Teams of 2",
            Part(
                id = fid(6, 1, 1), kind = PartKind.METCON, format = ForTime(capSec = 1800),
                hints = PartHints(partner = PartnerHint(2, splitRule = "split as needed", synchro = true, notes = "P2 holds plank while P1 snatches")),
                body = listOf(
                    Item(node(6, 1), "air_bike", "Bike", measure = Measure(MeasureKind.CALORIES, 100.0), notes = "Board: 100/80 cal (split)"),
                    Item(
                        node(6, 2), "burpee_box_jump_over", "Burpee box jump-over", measure = reps(60),
                        height = Length(24.0, LengthUnit.IN), notes = "Board: 24/20 in", modifiers = listOf("synchro"),
                    ),
                    Item(
                        node(6, 3), "dumbbell_snatch", "DB snatch", measure = reps(40), load = AbsoluteLoad(lb(50.0)),
                        implement = "dumbbell", side = Side.ALTERNATING, notes = "Board: 50/35 lb; switch every 10",
                    ),
                ),
                scoring = Scoring(ScoreType.TIME),
            ),
        ),
        review = { w -> ReviewActions.acceptRequiredFallbacks(w) },
    )

    val h7 = HFixture(
        7,
        workout(
            7, "4 x 4:00",
            Part(
                id = fid(7, 1, 1), kind = PartKind.METCON, format = Intervals(rounds = 4, workSec = 240, restSec = 120, inner = InnerMode.AMRAP),
                body = listOf(
                    Item(node(7, 1), "run", "Run", measure = Measure(MeasureKind.DISTANCE, 200.0, unit = DistanceUnit.M), role = ItemRole.BUY_IN),
                    Group(
                        node(7, 2), GroupKind.ROUNDS,
                        children = listOf(
                            Item(node(7, 3), "deadlift", "Deadlift", measure = reps(5), load = AbsoluteLoad(lb(225.0)), notes = "Board: 225/155 lb", implement = "barbell"),
                            Item(node(7, 4), "burpee", "Burpee", measure = reps(10)),
                        ),
                    ),
                ),
                scoring = Scoring(ScoreType.ROUNDS_REPS, aggregation = AggregationOp.SUM),
            ),
        ),
        review = { it },
    )

    val h8 = HFixture(
        8,
        workout(
            8, "Superset, wave, ladder",
            Part(
                id = fid(8, 1, 1), label = "A", kind = PartKind.STRENGTH, format = StrengthSets(),
                body = listOf(
                    Group(
                        node(8, 1), GroupKind.SUPERSET,
                        children = listOf(
                            Item(
                                node(8, 2), "front_squat", "Front squat", implement = "barbell", modifiers = listOf("tempo"),
                                sets = (1..4).map { StrengthSet(set(8, it), RepTarget(5), tempo = "32X1", rpe = 7.0) },
                            ),
                            Item(
                                node(8, 3), "strict_chest_to_bar_pull_up", "Strict C2B",
                                sets = (5..8).map { StrengthSet(set(8, it), RepTarget(5, upTo = 8), restSec = 90) },
                            ),
                        ),
                    ),
                ),
                scoring = Scoring(ScoreType.LOAD),
            ),
            Part(
                id = fid(8, 1, 2), label = "B", kind = PartKind.STRENGTH, format = StrengthSets(),
                body = listOf(
                    Item(
                        node(8, 4), "bench_press", "Bench press", implement = "barbell",
                        sets = listOf(3, 2, 1, 3, 2, 1).mapIndexed { i, r -> StrengthSet(set(8, 9 + i), RepTarget(r)) },
                    ),
                ),
                scoring = Scoring(ScoreType.LOAD),
            ),
            Part(
                id = fid(8, 1, 3), label = "WOD", kind = PartKind.METCON, format = ForTime(capSec = 720),
                body = listOf(
                    Group(
                        node(8, 5), GroupKind.ROUNDS, repeat = 10, repScheme = (1..10).toList(),
                        children = listOf(
                            Item(node(8, 6), "deadlift", "Deadlift", measure = scheme, load = PercentOfBodyweight(150.0), implement = "barbell"),
                            Item(node(8, 7), "box_jump", "Box jump", measure = scheme, height = Length(30.0, LengthUnit.IN), notes = "Board: 30/24 in"),
                        ),
                    ),
                ),
                scoring = Scoring(ScoreType.TIME),
            ),
        ),
        review = ReviewActions::acceptRequiredFallbacks,
    )

    val h9 = HFixture(
        9,
        workout(
            9, "Step-ups, row, squat",
            Part(
                id = fid(9, 1, 1), label = "1", kind = PartKind.METCON, format = ForTime(capSec = 3600),
                body = listOf(
                    Item(node(9, 1), "box_step_up", "Box step-up", measure = reps(1000)),
                    Item(node(9, 2), "row", "Row", measure = Measure(MeasureKind.DISTANCE, 2000.0, unit = DistanceUnit.M)),
                ),
                scoring = Scoring(ScoreType.TIME),
            ),
            Part(
                id = fid(9, 1, 2), label = "2", kind = PartKind.STRENGTH, format = StrengthSets(),
                body = listOf(
                    Item(node(9, 3), "back_squat", "Back squat", implement = "barbell", sets = (1..3).map { StrengthSet(set(9, it), RepTarget(3), PercentOfMax(75.0)) }),
                ),
                scoring = Scoring(ScoreType.LOAD),
            ),
        ),
        review = { it },
        // H9: no stored 1RM — the load must stay a manual entry, never 0 or a guess.
        profile = TestSupport.profile.copy(repMaxes = emptyList()),
    )

    val h10 = HFixture(
        10,
        workout(
            10, "AMRAP 10",
            Part(
                id = fid(10, 1, 1), kind = PartKind.METCON, format = Amrap(600),
                body = listOf(
                    Item(node(10, 1), "pull_up", "Pull-up", measure = reps(5)),
                    Item(node(10, 2), "push_up", "Push-up", measure = reps(10)),
                    Item(node(10, 3), "air_squat", "Air squat", measure = reps(15)),
                ),
                scoring = Scoring(ScoreType.ROUNDS_REPS),
            ),
        ),
        review = { it },
    )

    val all = listOf(h1, h2, h3, h4, h5, h6, h7, h8, h9, h10)

    /** Absolute-load helper for ad-hoc tests. */
    fun abs(v: Double, u: MassUnit) = AbsoluteLoad(Mass(v, u))
}

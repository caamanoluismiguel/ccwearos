package com.caamano.ccwearos.presentation.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.caamano.ccwearos.data.ToolEvent

// 12×12 pixel-grid icons in the mascot's style. Each icon is authored as ASCII
// art ('#' = lit pixel) so the shapes stay reviewable in code, and converted
// to an ImageVector of square runs. Replaces the Unicode glyphs (⌘ ✎ ▤ ⌕) and
// the 📟 emoji, which rendered inconsistently across Wear OS fonts.
//
// Tint them with `Icon(..., tint = ...)`; the path colour is a placeholder.
object PixelIcons {

    private fun pixelIcon(name: String, rows: List<String>): ImageVector {
        require(rows.size == 12 && rows.all { it.length == 12 }) { "$name must be 12×12" }
        return ImageVector.Builder(
            name = name,
            defaultWidth = 12.dp,
            defaultHeight = 12.dp,
            viewportWidth = 12f,
            viewportHeight = 12f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                rows.forEachIndexed { y, row ->
                    var x = 0
                    while (x < row.length) {
                        if (row[x] == '#') {
                            val start = x
                            while (x < row.length && row[x] == '#') x++
                            moveTo(start.toFloat(), y.toFloat())
                            lineTo(x.toFloat(), y.toFloat())
                            lineTo(x.toFloat(), y + 1f)
                            lineTo(start.toFloat(), y + 1f)
                            close()
                        } else {
                            x++
                        }
                    }
                }
            }
        }.build()
    }

    val Mic: ImageVector by lazy {
        pixelIcon(
            "Mic",
            listOf(
                "....####....",
                "...######...",
                "...######...",
                "...######...",
                "...######...",
                ".#.######.#.",
                ".#.######.#.",
                ".##.####.##.",
                "..##....##..",
                "....####....",
                ".....##.....",
                "...######...",
            ),
        )
    }

    /** Bash / shell. */
    val Terminal: ImageVector by lazy {
        pixelIcon(
            "Terminal",
            listOf(
                "############",
                "#..........#",
                "#.#........#",
                "#..#.......#",
                "#...#......#",
                "#..#.......#",
                "#.#..####..#",
                "#..........#",
                "#..........#",
                "############",
                "............",
                "............",
            ),
        )
    }

    /** Edit / Write. */
    val Pencil: ImageVector by lazy {
        pixelIcon(
            "Pencil",
            listOf(
                ".........##.",
                "........####",
                ".......####.",
                "......####..",
                ".....####...",
                "....####....",
                "...####.....",
                "..####......",
                ".####.......",
                ".###........",
                "##..........",
                "#...........",
            ),
        )
    }

    /** Read. */
    val Document: ImageVector by lazy {
        pixelIcon(
            "Document",
            listOf(
                ".#######....",
                ".#.....##...",
                ".#.....#.#..",
                ".#.....####.",
                ".#.####...#.",
                ".#........#.",
                ".#.######.#.",
                ".#........#.",
                ".#.######.#.",
                ".#........#.",
                ".##########.",
                "............",
            ),
        )
    }

    /** Grep / Glob. */
    val Search: ImageVector by lazy {
        pixelIcon(
            "Search",
            listOf(
                "..####......",
                ".#....#.....",
                "#......#....",
                "#......#....",
                "#......#....",
                "#......#....",
                ".#....#.....",
                "..#####.....",
                "......###...",
                ".......###..",
                "........###.",
                ".........##.",
            ),
        )
    }

    /** WebFetch / WebSearch. */
    val Globe: ImageVector by lazy {
        pixelIcon(
            "Globe",
            listOf(
                "...######...",
                "..#..##..#..",
                ".#..#..#..#.",
                "############",
                "#...#..#...#",
                "#...#..#...#",
                "#...#..#...#",
                "#...#..#...#",
                "############",
                ".#..#..#..#.",
                "..#..##..#..",
                "...######...",
            ),
        )
    }

    /** Task (sub-agent). */
    val Stack: ImageVector by lazy {
        pixelIcon(
            "Stack",
            listOf(
                "............",
                "....####....",
                "..##....##..",
                "##........##",
                "..##....##..",
                "#...####...#",
                ".##......##.",
                "#..##..##..#",
                ".##..##..##.",
                "...##..##...",
                ".....##.....",
                "............",
            ),
        )
    }

    /** Fallback for unknown tools. */
    val Dot: ImageVector by lazy {
        pixelIcon(
            "Dot",
            listOf(
                "............",
                "............",
                "............",
                "............",
                "....####....",
                "....####....",
                "....####....",
                "....####....",
                "............",
                "............",
                "............",
                "............",
            ),
        )
    }

    /** Shared session / bridge (replaces the 📟 emoji). */
    val Link: ImageVector by lazy {
        pixelIcon(
            "Link",
            listOf(
                "............",
                "............",
                ".####..####.",
                "#....##....#",
                "#..........#",
                "#...####...#",
                "#..........#",
                "#....##....#",
                ".####..####.",
                "............",
                "............",
                "............",
            ),
        )
    }

    val Check: ImageVector by lazy {
        pixelIcon(
            "Check",
            listOf(
                "............",
                "..........##",
                ".........###",
                "........###.",
                ".......###..",
                "##....###...",
                "###..###....",
                ".######.....",
                "..####......",
                "...##.......",
                "............",
                "............",
            ),
        )
    }

    val Cross: ImageVector by lazy {
        pixelIcon(
            "Cross",
            listOf(
                "............",
                ".##......##.",
                ".###....###.",
                "..###..###..",
                "...######...",
                "....####....",
                "....####....",
                "...######...",
                "..###..###..",
                ".###....###.",
                ".##......##.",
                "............",
            ),
        )
    }

    val Stop: ImageVector by lazy {
        pixelIcon(
            "Stop",
            listOf(
                "............",
                "............",
                "..########..",
                "..########..",
                "..########..",
                "..########..",
                "..########..",
                "..########..",
                "..########..",
                "..########..",
                "............",
                "............",
            ),
        )
    }

    /** New conversation (replaces ↻). */
    val Refresh: ImageVector by lazy {
        pixelIcon(
            "Refresh",
            listOf(
                "....####.#..",
                "..##....###.",
                ".#.....####.",
                "#...........",
                "#...........",
                "#..........#",
                "#..........#",
                "...........#",
                "...........#",
                ".####.....#.",
                ".###....##..",
                "..#.####....",
            ),
        )
    }
}

/** Pixel icon for a tool event (Bash, Edit, Read, …). */
fun ToolEvent.pixelIcon(): ImageVector = when (tool.replace("\\s+".toRegex(), "")) {
    "Bash" -> PixelIcons.Terminal
    "Edit", "Write", "MultiEdit", "NotebookEdit" -> PixelIcons.Pencil
    "Read" -> PixelIcons.Document
    "Grep", "Glob" -> PixelIcons.Search
    "WebFetch", "WebSearch" -> PixelIcons.Globe
    "Task", "Agent" -> PixelIcons.Stack
    else -> PixelIcons.Dot
}

package org.necmobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val LibraryBackground = Color(0xff161b19)
internal val LibrarySurface = Color(0xff202724)
internal val LibraryRaised = Color(0xff282f2b)
internal val LibraryLine = Color(0xff343e38)
internal val LibraryText = Color(0xffedf2ee)
internal val LibraryMuted = Color(0xffa6b2a9)
internal val LibraryAccent = Color(0xffc7ecaa)
private val LocalLibraryIcons = staticCompositionLocalOf<ImageBitmap> { error("LibraryTheme is required") }

@Composable internal fun LibraryTheme(content: @Composable () -> Unit) {
    val icons = ImageBitmap.imageResource(R.drawable.ui_icons)
    CompositionLocalProvider(LocalLibraryIcons provides icons) {
      MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LibraryAccent, onPrimary = Color(0xff20331a),
            primaryContainer = Color(0xff2b392d), onPrimaryContainer = LibraryAccent,
            secondary = LibraryAccent, secondaryContainer = Color(0xff344635), onSecondaryContainer = LibraryAccent,
            background = LibraryBackground, onBackground = LibraryText,
            surface = LibrarySurface, onSurface = LibraryText, surfaceVariant = LibraryRaised,
            onSurfaceVariant = LibraryMuted, outline = LibraryLine, outlineVariant = LibraryLine,
            surfaceContainer = Color(0xff252d27), surfaceContainerHigh = LibraryRaised,
            surfaceContainerLow = Color(0xff252d27), surfaceContainerLowest = Color(0xff101512),
            surfaceContainerHighest = Color(0xff303b35), surfaceDim = LibraryBackground, surfaceBright = LibraryLine,
            inverseSurface = Color(0xff354437), inverseOnSurface = Color(0xffeff7ed), inversePrimary = LibraryAccent,
            scrim = Color(0xff090d0b),
            error = Color(0xfff5b8aa)
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(9.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(15.dp), large = RoundedCornerShape(17.dp), extraLarge = RoundedCornerShape(22.dp)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
        ), content = content
      )
    }
}

internal enum class LibrarySymbol {
    Monitor, Library, Settings, Plus, Search, Filter, Play, More, Back, Next, Close, Hard, Floppy, Chip, Audio, Book, Folder, Check
}

@Composable internal fun LibraryIcon(symbol: LibrarySymbol, modifier: Modifier = Modifier.size(21.dp), tint: Color = LocalContentColor.current) {
    val icons = LocalLibraryIcons.current
    Canvas(modifier) {
        val cell = icons.width / LibrarySymbol.entries.size
        drawImage(icons, srcOffset = IntOffset(symbol.ordinal * cell, 0), srcSize = IntSize(cell, icons.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable internal fun RecentDiskArt(floppy: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 160f
        val center = Offset(size.width * .62f, size.height * .52f)
        for (step in 0..9) {
            val position = step * 20 * unit
            drawLine(Color(0x11bad1ae), Offset(position, 0f), Offset(position, size.height), unit)
            drawLine(Color(0x11bad1ae), Offset(0f, position), Offset(size.width, position), unit)
        }
        rotate(15f, center) {
            val width = (if (floppy) 97 else 83) * unit
            val height = (if (floppy) 97 else 109) * unit
            val origin = center - Offset(width / 2, height / 2)
            drawRoundRect(Color(0xff1e2b22), origin + Offset(0f, 6 * unit), Size(width, height), CornerRadius(9 * unit))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xff7b906b), Color(0xff354838)), origin, origin + Offset(width, height)),
                origin, Size(width, height), CornerRadius(9 * unit))
            drawRoundRect(Color(0xff8ca482), origin, Size(width, height), CornerRadius(9 * unit), style = Stroke(unit))
            if (floppy) {
                drawRoundRect(Color(0xff98a88e), origin + Offset(25 * unit, 0f), Size(47 * unit, 30 * unit), CornerRadius(4 * unit))
                drawRect(Color(0xff263a2a), origin + Offset(54 * unit, 0f), Size(10 * unit, 26 * unit))
                drawRoundRect(Color(0xffbfd1b5), origin + Offset(13 * unit, 52 * unit), Size(width - 26 * unit, 35 * unit), CornerRadius(4 * unit))
                for (line in 1..3) drawLine(Color(0xff819779), origin + Offset(13 * unit, (52 + line * 9) * unit), origin + Offset(width - 13 * unit, (52 + line * 9) * unit), unit)
            } else {
                val platter = origin + Offset(width / 2, 42 * unit)
                drawCircle(Brush.radialGradient(listOf(Color(0xffa8ba99), Color(0xff516a4e)), platter, 31 * unit), 31 * unit, platter)
                for (ring in 1..7) drawCircle(Color(0x55c4dcb7), ring * 4 * unit, platter, style = Stroke(unit))
                drawCircle(Color(0xffcad9b9), 5 * unit, platter)
                for (vent in 0..8) drawRoundRect(Color(0xff87a279), origin + Offset((14 + vent * 6) * unit, height - 19 * unit), Size(3 * unit, 7 * unit), CornerRadius(unit))
            }
        }
    }
}

package ru.bysonic.chistytv.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object C {
  val Bg = Color(0xFF0A0E1A)
  val Surface = Color(0xFF121829)
  val SurfaceHigh = Color(0xFF1A2236)
  val Stroke = Color(0x14FFFFFF)
  val StrokeStrong = Color(0x26FFFFFF)

  val Violet = Color(0xFF7C5CFF)
  val Teal = Color(0xFF2DD4BF)
  val Pink = Color(0xFFF472B6)
  val Sky = Color(0xFF38BDF8)

  val Ok = Color(0xFF34D399)
  val Warn = Color(0xFFFBBF24)
  val Fail = Color(0xFFF87171)

  val Text = Color(0xFFEEF2FF)
  val TextDim = Color(0xFFA3ADC8)
  val TextMuted = Color(0xFF6B7591)

  val Brand = Brush.linearGradient(listOf(Violet, Teal))
  val BrandVertical = Brush.verticalGradient(listOf(Violet, Teal))
}

/** Цвета значков действий: у каждого раздела своя пара */
object Accent {
  val Clean = listOf(Color(0xFF7C5CFF), Color(0xFFB794F6))
  val YouTube = listOf(Color(0xFFFF4D6D), Color(0xFFFF8A5B))
  val Home = listOf(Color(0xFF2DD4BF), Color(0xFF38BDF8))
  val Dns = listOf(Color(0xFF34D399), Color(0xFF2DD4BF))
  val Restore = listOf(Color(0xFFFBBF24), Color(0xFFF59E0B))
}

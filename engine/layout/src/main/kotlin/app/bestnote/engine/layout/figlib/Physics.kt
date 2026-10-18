package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.angleOf
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Companion.mid
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** FIG-LIB physics: inclines, blocks and forces, springs, pulleys, pendulums, circuits, optics, waves, fields. */
internal object Physics {
    val all: List<FigElement> by lazy {
        listOf(
            FigElement("incline", Subject.Physics, "a=deg; up=L|R; blk=[m]; at=0-1; f=[label, n|g|up|down|h|deg above slope]…; ang=[label]; h|len|base=[label]; comp (dashed mg components, not forces); v=[label,up|down]; pulley=[m2]", listOf("ramp", "inclinedplane", "slope", "wedge"), ::incline),
            FigElement("fbd", Subject.Physics, "body=[box|dot, label]; f=[label, deg|up|down|left|right, len]…; ang=[label, force]; axes", listOf("freebody", "freebodydiagram", "forces", "forcediagram"), ::fbd),
            FigElement("blocks", Subject.Physics, "m=[label]… left→right; link=touch|rope|spring; f=[label, dir, block#]…; wall=L|R; mu=[label]; v=[label, block#]", listOf("block", "mass", "cart", "carts"), ::blocks),
            FigElement("spring", Subject.Physics, "state=relaxed|compressed|stretched; coils; k|m|x=[label]; eq; vertical; f=[label, dir]", listOf("springs", "springmass", "oscillator", "hooke"), ::spring),
            FigElement("pulley", Subject.Physics, "type=atwood|table|incline; m=[label]…; T; g; a=deg", listOf("pulleys", "atwood", "rope", "string"), ::pulley),
            FigElement("pendulum", Subject.Physics, "a=deg; L|m|h=[label]; f=[label, t|g]; path", listOf("pend", "simplependulum", "bob"), ::pendulum),
            FigElement("circuit", Subject.Physics, "rows [R|C|L|bat|cell|sw|swc|bulb|A|V|G|D|wire, label, node, node]; I=[label, node, node]", listOf("circuits", "schematic", "electric", "electriccircuit", "netlist"), Circuit::draw),
            FigElement("lens", Subject.Physics, "type=convex|concave; f=n; obj=[dist, height]; rays=1-3; 2f", listOf("lenses", "optics", "thinlens", "convexlens", "concavelens"), Optics::lens),
            FigElement("mirror", Subject.Physics, "type=concave|convex|plane; f=n; obj=[dist, height]", listOf("mirrors", "sphericalmirror", "planemirror"), Optics::mirror),
            FigElement("refraction", Subject.Physics, "n=[n1, n2]; i=deg; r=deg; reflect; ang=[θ1, θ2]", listOf("snell", "refract", "reflection", "interface", "ray"), Optics::refraction),
            FigElement("wave", Subject.Physics, "type=transverse|standing|longitudinal|pulse; n=wavelengths; A|lam|v|x|y=[label]", listOf("waves", "sine", "sinewave", "oscillation"), Waves::wave),
            FigElement("field", Subject.Physics, "q=[±, x, y]… (-5..5); uniform=deg; plates; B=in|out; wire=in|out; E=[label]", listOf("fieldlines", "electricfield", "magneticfield", "charges"), Waves::field),
            FigElement("vectors", Subject.Physics, "v=[label, x, y, x0, y0]…; sum=[label]; comp; grid; ang=[label, vec#]", listOf("vector", "vectoraddition", "resultant", "components"), ::vectors),
        )
    }

    /** A force direction word or angle → a unit vector; [slope] and [normal] are the local slope frame. */
    private fun forceDir(v: Any?, slope: Vec2, normal: Vec2, high: Vec2): Vec2? {
        Params.numOf(v)?.let { a ->
            // Degrees above the up-slope direction, turning towards the outward normal.
            val turn = if (slope.rot90().dot(normal) > 0f) a else -a
            return dir(angleOf(slope) + turn)
        }
        val s = v?.toString()?.lowercase()?.trim() ?: return null
        return when (s) {
            "n", "normal", "perp", "⟂" -> normal
            "g", "mg", "w", "weight", "gravity", "v-", "vdown" -> Vec2(0f, -1f)
            "up", "+s", "s", "upslope", "along", "t", "tension", "pull" -> slope
            "dn", "down", "d", "-s", "downslope", "back" -> slope * -1f
            "h", "push", "h+", "x" -> high
            "-h", "h-", "-x" -> high * -1f
            "v", "u", "vertical", "lift", "v+" -> Vec2(0f, 1f)
            "into", "-n" -> normal * -1f
            else -> null
        }
    }

    private fun incline(p: Params, pen: Pen): Boolean {
        val th = p.num("a", "angle", "theta", default = 30f, range = 5f..80f)
        val up = p.choice("up", "high", "side", options = listOf("l", "r", "left", "right"), default = "r").first()
        val sx = if (up == 'r') 1f else -1f
        val l = 170f
        val b = l * kotlin.math.cos(Math.toRadians(th.toDouble())).toFloat()
        val h = l * sin(Math.toRadians(th.toDouble())).toFloat()
        // Low corner (the angle), right-angle corner, top: the slope rises towards the high side.
        val low = Vec2(0f, 0f)
        val corner = Vec2(sx * b, 0f)
        val top = Vec2(sx * b, h)
        pen.poly(listOf(low, corner, top), closed = true)
        val u = unit(top - low)
        val nrm = if (sx > 0) u.rot90() else u.rot90() * -1f
        val high = Vec2(sx, 0f)
        if (p.flag("gnd", "ground", "floor")) pen.surface(Vec2(-sx * 25f, 0f), low, side = if (sx > 0) -1 else 1)
        // The angle at the low corner, drawn once, labelled once.
        val angText = p.str("ang", "anglelabel") ?: p.str("a", "angle")?.let { if (Params.numOf(it) != null) "${Params.textOf(Params.numOf(it)!!.toDouble())}^\\circ" else it } ?: "\\theta"
        val ground = if (sx > 0) 0f else 180f
        pen.angle(low, ground, angleOf(u), r = 22f, label = angText)
        pen.rightAngle(corner, if (sx > 0) 180f else 0f, 90f, size = 8f)
        p.str("h", "height")?.let { pen.dimension(corner, top, it, offset = -sx * 10f) }
        p.str("len", "l", "length", "d")?.let { pen.dimension(low, top, it, offset = 12f * sx) }
        p.str("base", "b", "x")?.let { pen.dimension(corner, low, it, offset = -sx * 10f * -1f) }
        val hasBlock = p.has("blk", "block", "m", "mass") || p.has("f", "force")
        if (!hasBlock) return true
        val at = p.num("at", "pos", default = 0.55f, range = 0.15f..0.85f)
        val bw = 36f
        val bh = 24f
        val contact = low + u * (l * at)
        val c = contact + nrm * (bh / 2)
        pen.rect(c, bw, bh, angleOf(u))
        val reach = { d: Vec2 -> abs(d.dot(u)) * bw / 2 + abs(d.dot(nrm)) * bh / 2 }
        val seen = HashSet<String>()
        val dirs = ArrayList<Vec2>()
        for (r in p.all("f", "force")) {
            val name = Params.textOf(r.getOrNull(0)) ?: continue
            if (!seen.add(name)) continue
            val d = forceDir(r.getOrNull(1), u, nrm, high) ?: guessDir(name, u, nrm) ?: continue
            dirs += d
            val mag = Params.numOf(r.getOrNull(2))?.coerceIn(0.4f, 2.5f) ?: 1f
            val tip = c + d * (reach(d) + 30f * mag)
            pen.arrow(c, tip)
            pen.label(name, tip, d, gap = 1.5f, role = Role.Vector)
        }
        if (dirs.isNotEmpty()) pen.dot(c, 1.3f, Role.Vector)
        pen.label(p.str("blk", "block", "m", "mass"), clearOf(c, dirs, u * (bw * 0.27f), nrm * (bh * 0.2f)), inside = true)
        if (p.flag("comp", "components")) {
            val g = 30f + bh / 2
            val s = sin(Math.toRadians(th.toDouble())).toFloat()
            val co = kotlin.math.cos(Math.toRadians(th.toDouble())).toFloat()
            val along = c - u * (g * s + bw / 2 * 0.6f)
            val into = c - nrm * (g * co + bh / 2 * 0.6f)
            pen.arrow(c, along, Role.Note, dashed = true)
            pen.arrow(c, into, Role.Note, dashed = true)
            pen.label("mg\\sin\\theta", along, u * -1f, role = Role.Note)
            pen.label("mg\\cos\\theta", into, nrm * -1f, role = Role.Note)
        }
        p.first("v", "vel", "velocity")?.let { r ->
            val name = Params.textOf(r.getOrNull(0)) ?: "v"
            val d = if (r.getOrNull(1)?.toString()?.lowercase()?.startsWith("d") == true) u * -1f else u
            val base = c + nrm * (bh / 2 + 10f) - d * 12f
            pen.arrow(base, base + d * 26f, Role.Accent)
            pen.label(name, base + d * 26f, nrm, role = Role.Accent)
        }
        p.str("pulley", "hang", "hanging")?.let { m2 ->
            val pc = top + u * 9f + nrm * 9f
            pen.circle(pc, 9f)
            pen.dot(pc, 1.2f)
            val face = c + u * (bw / 2)
            pen.line(face, face + u * (top.dist(face) + 9f) )
            val side = pc + high * 9f
            val hang = side + Vec2(0f, -60f)
            pen.line(side, hang)
            pen.rect(hang - Vec2(0f, 12f), 22f, 24f)
            pen.label(m2, hang - Vec2(0f, 12f), inside = true)
        }
        return true
    }

    /** Of the four quadrant spots c ± [a] ± [b], the one farthest from the force arrows leaving [c] along [dirs]. */
    private fun clearOf(c: Vec2, dirs: List<Vec2>, a: Vec2, b: Vec2): Vec2 {
        if (dirs.isEmpty()) return c
        val spots = listOf(c + a + b, c + a - b, c - a + b, c - a - b)
        fun gap(q: Vec2) = dirs.minOf { d -> val t = (q - c).dot(d).coerceAtLeast(0f); (q - (c + d * t)).length() }
        return spots.maxBy(::gap)
    }

    /** A force with no direction given: the usual meaning of its name. */
    private fun guessDir(name: String, u: Vec2, n: Vec2): Vec2? {
        val s = name.lowercase().replace("\\", "").replace("{", "").replace("}", "")
        return when {
            s.startsWith("n") -> n
            s.startsWith("mg") || s.startsWith("w") || s == "g" || s.startsWith("f_g") -> Vec2(0f, -1f)
            s.startsWith("f_k") || s.startsWith("f_s") || s.startsWith("f_f") || s == "f" || s.startsWith("fr") -> u * -1f
            s.startsWith("t") -> u
            else -> null
        }
    }

    private fun compass(v: Any?): Float? = Params.numOf(v) ?: when (v?.toString()?.lowercase()?.trim()) {
        "right", "r", "e", "east", "+x", "x" -> 0f
        "up", "u", "n", "north", "+y", "y", "v" -> 90f
        "left", "l", "w", "west", "-x" -> 180f
        "down", "d", "s", "south", "-y", "g", "mg" -> 270f
        else -> null
    }

    private fun fbd(p: Params, pen: Pen): Boolean {
        val forces = p.all("f", "force")
        if (forces.isEmpty()) return false
        val body = p.choice("body", options = listOf("box", "dot", "point", "block"), default = "box")
        val c = Vec2(0f, 0f)
        val half = if (body == "box" || body == "block") 18f else 0f
        if (half > 0f) pen.rect(c, 2 * half, 2 * half) else pen.dot(c, 2.4f)
        val bodyName = p.first("body")?.getOrNull(1)?.let(Params::textOf) ?: p.str("m", "mass")
        if (p.flag("axes")) {
            pen.line(Vec2(-75f, 0f), Vec2(75f, 0f), Role.Note, dashed = true)
            pen.line(Vec2(0f, -75f), Vec2(0f, 75f), Role.Note, dashed = true)
        }
        val seen = HashSet<String>()
        val angles = HashMap<String, Float>()
        for (r in forces) {
            val name = Params.textOf(r.getOrNull(0)) ?: continue
            if (!seen.add(name)) continue
            val a = compass(r.getOrNull(1)) ?: guessDir(name, Vec2(1f, 0f), Vec2(0f, 1f))?.let(::angleOf) ?: continue
            val mag = Params.numOf(r.getOrNull(2))?.coerceIn(0.4f, 2.5f) ?: 1f
            val d = dir(a)
            val reach = if (half > 0f) half / max(abs(d.x), abs(d.y)) else 2f
            val tip = c + d * (reach + 40f * mag)
            pen.arrow(c + d * (if (half > 0f) 0f else 2f), tip)
            pen.label(name, tip, d, gap = 1.5f, role = Role.Vector)
            angles[name] = a
        }
        if (half > 0f) pen.label(bodyName, clearOf(c, angles.values.map(::dir), Vec2(half * 0.55f, 0f), Vec2(0f, half * 0.55f)), inside = true)
        else pen.label(bodyName, c, Vec2(-0.7f, -0.7f), gap = 3f)
        for (r in p.all("ang", "angle")) {
            val text = Params.textOf(r.getOrNull(0)) ?: continue
            val a = angles[Params.textOf(r.getOrNull(1)) ?: ""] ?: angles.values.firstOrNull { abs(it % 90f) > 1f } ?: continue
            // From the nearer horizontal.
            val ref = if (Pen.norm360(a) in 90f..270f) 180f else 0f
            pen.angle(c, ref, a, r = half + 16f, label = text)
        }
        return true
    }

    private fun blocks(p: Params, pen: Pen): Boolean {
        val names = p.all("m", "blk", "block", "mass").map { Params.textOf(it.getOrNull(0)) ?: "" }.ifEmpty { listOf("m") }.take(4)
        val link = p.choice("link", "join", options = listOf("touch", "rope", "spring", "rod", "none"), default = "rope")
        val w = 34f
        val h = 26f
        val gap = if (link == "touch") 0f else 34f
        val centres = names.indices.map { i -> Vec2(i * (w + gap), h / 2) }
        val x0 = -w / 2 - 30f
        val x1 = centres.last().x + w / 2 + 40f
        val wall = p.str("wall")?.lowercase()?.firstOrNull()
        val floorL = if (wall == 'l') -w / 2 - 45f else x0
        val floorR = if (wall == 'r') centres.last().x + w / 2 + 45f else x1
        pen.surface(Vec2(floorL, 0f), Vec2(floorR, 0f), side = -1)
        if (wall == 'l') pen.surface(Vec2(floorL, 0f), Vec2(floorL, 60f), side = 1)
        if (wall == 'r') pen.surface(Vec2(floorR, 0f), Vec2(floorR, 60f), side = -1)
        for ((i, c) in centres.withIndex()) {
            pen.rect(c, w, h)
            pen.label(names[i].takeIf { it.isNotEmpty() }, c, inside = true)
            if (i > 0 && gap > 0f) {
                val a = Vec2(centres[i - 1].x + w / 2, h / 2)
                val b = Vec2(c.x - w / 2, h / 2)
                when (link) {
                    "spring" -> pen.spring(a, b, 5, 5f)
                    "rope", "rod" -> pen.line(a, b)
                    else -> Unit
                }
            }
        }
        if (wall == 'l' && link == "spring") pen.spring(Vec2(floorL, h / 2), Vec2(centres[0].x - w / 2, h / 2), 5, 5f)
        p.str("mu", "friction")?.let { pen.label(it, Vec2(centres.last().x + w / 2 + 14f, 0f), Vec2(0f, -1f), gap = 6f) }
        val seen = HashSet<String>()
        for (r in p.all("f", "force")) {
            val name = Params.textOf(r.getOrNull(0)) ?: continue
            val i = ((Params.numOf(r.getOrNull(2)) ?: 1f).toInt() - 1).coerceIn(0, centres.lastIndex)
            if (!seen.add("$name@$i")) continue
            val a = compass(r.getOrNull(1)) ?: guessDir(name, Vec2(-1f, 0f), Vec2(0f, 1f))?.let(::angleOf) ?: continue
            val d = dir(a)
            val c = centres[i]
            val reach = min(abs(w / 2 / (if (abs(d.x) > 1e-3f) d.x else 1e-3f)), abs(h / 2 / (if (abs(d.y) > 1e-3f) d.y else 1e-3f)))
            // Towards a neighbour (or the wall) it is a push: drawn coming in to the far face. Otherwise a pull from the centre.
            val blocked = (d.x < -0.5f && (i > 0 || wall == 'l')) || (d.x > 0.5f && (i < centres.lastIndex || wall == 'r'))
            if (blocked) {
                val face = c - d * reach
                pen.arrow(face - d * 28f, face)
                pen.label(name, face - d * 28f, d * -1f, gap = 1.5f, role = Role.Vector)
            } else {
                val tip = c + d * (reach + 28f)
                pen.arrow(c, tip)
                pen.label(name, tip, d, gap = 1.5f, role = Role.Vector)
            }
        }
        p.first("v", "vel")?.let { r ->
            val i = ((Params.numOf(r.getOrNull(1)) ?: 1f).toInt() - 1).coerceIn(0, centres.lastIndex)
            val s = centres[i] + Vec2(-12f, h / 2 + 10f)
            pen.arrow(s, s + Vec2(26f, 0f), Role.Accent)
            pen.label(Params.textOf(r.getOrNull(0)) ?: "v", s + Vec2(26f, 0f), Vec2(1f, 0f), role = Role.Accent)
        }
        return true
    }

    private fun spring(p: Params, pen: Pen): Boolean {
        val state = p.choice("state", options = listOf("relaxed", "compressed", "stretched", "natural", "rest"), default = "relaxed")
        val coils = p.int("coils", "n", default = 7, range = 3..20)
        val vertical = p.flag("vertical", "hang", "hanging") || p.choice("dir", "orient", options = listOf("h", "v", "horizontal", "vertical"), default = "h").startsWith("v")
        val rest = 90f
        val len = when (state) { "compressed" -> rest * 0.6f; "stretched" -> rest * 1.4f; else -> rest }
        val bw = 30f
        val bh = 26f
        // Built horizontally (wall on the left), turned upright for a hanging spring.
        fun t(v: Vec2) = if (vertical) Vec2(v.y, -v.x) else v
        val a = Vec2(0f, bh / 2)
        val b = Vec2(len, bh / 2)
        if (vertical) {
            pen.surface(t(Vec2(0f, -25f)), t(Vec2(0f, bh + 25f)), side = -1)
        } else {
            pen.surface(Vec2(0f, 0f), Vec2(0f, bh + 30f), side = 1)
            if (p.flag("floor", "ground", default = true)) pen.surface(Vec2(0f, 0f), Vec2(len + bw + 50f, 0f), side = -1)
        }
        pen.spring(t(a), t(b), coils, 7f)
        val c = Vec2(len + bw / 2, bh / 2)
        pen.rect(t(c), bw, bh)
        pen.label(p.str("m", "blk", "mass"), t(c), inside = true)
        p.str("k")?.let { pen.label(it, t(mid(a, b) + Vec2(0f, 9f)), t(Vec2(0f, 1f)), gap = 2f) }
        val eqX = rest
        if (p.flag("eq", "equilibrium") || p.has("x", "dx")) {
            pen.line(t(Vec2(eqX + (if (state == "relaxed") 0f else 0f), -6f)), t(Vec2(eqX, bh + 22f)), Role.Note, dashed = true)
        }
        p.str("x", "dx", "displacement")?.let { x ->
            if (state != "relaxed") {
                val face = len
                pen.dimension(t(Vec2(eqX, bh + 14f)), t(Vec2(face, bh + 14f)), x, offset = 0f)
            }
        }
        for (r in p.all("f", "force")) {
            val name = Params.textOf(r.getOrNull(0)) ?: continue
            val a0 = compass(r.getOrNull(1)) ?: continue
            val d = t(dir(a0))
            val tip = t(c) + d * (bw / 2 + 28f)
            pen.arrow(t(c), tip)
            pen.label(name, tip, d, role = Role.Vector)
        }
        return true
    }

    private fun pulley(p: Params, pen: Pen): Boolean {
        val type = p.choice("type", options = listOf("atwood", "table", "incline"), default = if (p.all("m", "mass").size >= 2) "table" else "atwood")
        val ms = p.all("m", "mass", "blk").map { Params.textOf(it.getOrNull(0)) ?: "" }.ifEmpty { listOf("m_1", "m_2") }
        val r = 10f
        val showT = p.flag("t", "tension")
        val showG = p.flag("g", "weight", "weights")
        fun hang(top: Vec2, len: Float, name: String?) {
            val bc = top + Vec2(0f, -len - 13f)
            pen.line(top, top + Vec2(0f, -len))
            pen.rect(bc, 24f, 26f)
            pen.label(name, bc, inside = true)
            if (showT) {
                val s = top + Vec2(0f, -len)
                pen.arrow(s, s + Vec2(0f, 22f))
                pen.label("T", s + Vec2(0f, 22f), Vec2(1f, 0f), role = Role.Vector)
            }
            if (showG) {
                pen.arrow(bc, bc + Vec2(0f, -13f - 26f))
                pen.label("${name?.let { "$it " } ?: ""}g".replace(" g", "g"), bc + Vec2(0f, -39f), Vec2(0f, -1f), role = Role.Vector)
            }
        }
        when (type) {
            "atwood" -> {
                val pc = Vec2(0f, 0f)
                pen.surface(Vec2(-25f, 30f), Vec2(25f, 30f), side = 1)
                pen.line(Vec2(0f, 30f), pc)
                pen.circle(pc, r)
                pen.dot(pc, 1.2f)
                hang(Vec2(-r, 0f), 50f, ms.getOrNull(0))
                hang(Vec2(r, 0f), 80f, ms.getOrNull(1) ?: "m_2")
            }
            else -> {
                val th = if (type == "incline") p.num("a", "angle", default = 30f, range = 10f..70f) else 0f
                val tableTop = 0f
                val edge = Vec2(0f, tableTop)
                if (type == "table") {
                    pen.line(Vec2(-130f, tableTop), edge)
                    pen.line(edge, Vec2(0f, -90f))
                    pen.surface(Vec2(-140f, -90f), Vec2(20f, -90f), side = -1)
                } else {
                    val l = 150f
                    val low = Vec2(-l * kotlin.math.cos(Math.toRadians(th.toDouble())).toFloat(), -l * sin(Math.toRadians(th.toDouble())).toFloat())
                    pen.poly(listOf(low, edge, Vec2(0f, low.y)), closed = true)
                    pen.angle(low, 0f, th, r = 20f, label = p.str("ang") ?: "${Params.textOf(th.toDouble())}^\\circ")
                }
                val u = dir(th + 180f)
                val nrm = u.rot90() * -1f
                val pc = edge + nrm * (13f - r) - u * (r + 4f)
                pen.circle(pc, r)
                pen.dot(pc, 1.2f)
                pen.line(edge, pc)
                val bc = edge + u * 60f + nrm * 13f
                pen.rect(bc, 30f, 26f, angleOf(u * -1f))
                pen.label(ms.getOrNull(0), bc, inside = true)
                val face = bc - u * 15f
                pen.line(face, pc + nrm * r)
                if (showT) {
                    pen.arrow(face, face - u * 22f)
                    pen.label("T", face - u * 22f, nrm, role = Role.Vector)
                }
                hang(Vec2(pc.x + r, pc.y), 50f, ms.getOrNull(1) ?: "m_2")
            }
        }
        return true
    }

    private fun pendulum(p: Params, pen: Pen): Boolean {
        val th = p.num("a", "angle", "theta", default = 30f, range = 3f..80f)
        val len = 110f
        val pivot = Vec2(0f, 0f)
        pen.surface(Vec2(-30f, 0f), Vec2(30f, 0f), side = 1)
        pen.line(pivot, Vec2(0f, -len - 10f), Role.Note, dashed = true)
        val d = dir(270f + th)
        val bob = pivot + d * len
        pen.line(pivot, bob)
        pen.circle(bob, 8f)
        pen.label(p.str("m", "bob", "mass"), bob, d, gap = 3f)
        pen.angle(pivot, 270f, 270f + th, r = 26f, label = p.str("ang") ?: p.str("a", "angle")?.let { if (Params.numOf(it) != null) "${Params.textOf(Params.numOf(it)!!.toDouble())}^\\circ" else it } ?: "\\theta")
        p.str("l", "len", "length")?.let { pen.label(it, mid(pivot, bob), d.rot90() * -1f, gap = 3f) }
        if (p.flag("path", "swing", "arc")) pen.arc(pivot, len, 270f - th, 270f + th, Role.Note, dashed = true)
        p.str("h", "height")?.let {
            val low = Vec2(0f, -len)
            pen.dimension(Vec2(bob.x + 20f, low.y), Vec2(bob.x + 20f, bob.y), it, offset = 0f)
            pen.line(low, Vec2(bob.x + 24f, low.y), Role.Note, dashed = true)
        }
        for (r in p.all("f", "force")) {
            val name = Params.textOf(r.getOrNull(0)) ?: continue
            val s = r.getOrNull(1)?.toString()?.lowercase() ?: if (name.lowercase().startsWith("t")) "t" else "g"
            val dd = if (s.startsWith("t")) d * -1f else Vec2(0f, -1f)
            val tip = bob + dd * 36f
            pen.arrow(bob, tip)
            pen.label(name, tip, dd, role = Role.Vector)
        }
        return true
    }

    private fun vectors(p: Params, pen: Pen): Boolean {
        val vs = p.all("v", "vec", "vector").mapNotNull { r ->
            val name = Params.textOf(r.getOrNull(0))
            val x = Params.numOf(r.getOrNull(1)) ?: return@mapNotNull null
            val y = Params.numOf(r.getOrNull(2)) ?: return@mapNotNull null
            val x0 = Params.numOf(r.getOrNull(3)) ?: 0f
            val y0 = Params.numOf(r.getOrNull(4)) ?: 0f
            Triple(name, Vec2(x0, y0), Vec2(x0 + x, y0 + y))
        }.take(8)
        if (vs.isEmpty()) return false
        val sum = p.str("sum", "resultant", "r")
        val ends = vs.flatMap { listOf(it.second, it.third) } + Vec2(0f, 0f)
        val span = max(ends.maxOf { abs(it.x) }, ends.maxOf { abs(it.y) }).coerceAtLeast(1f)
        val k = 70f / span
        fun m(v: Vec2) = v * k
        if (p.flag("grid")) {
            val n = span.toInt().coerceIn(1, 12)
            for (i in -n..n) {
                pen.line(m(Vec2(i.toFloat(), -n.toFloat())), m(Vec2(i.toFloat(), n.toFloat())), Role.Note)
                pen.line(m(Vec2(-n.toFloat(), i.toFloat())), m(Vec2(n.toFloat(), i.toFloat())), Role.Note)
            }
        }
        if (p.flag("axes", default = true)) {
            val lo = m(Vec2(ends.minOf { it.x }.coerceAtMost(0f) - 0.3f * span, ends.minOf { it.y }.coerceAtMost(0f) - 0.3f * span))
            val hi = m(Vec2(ends.maxOf { it.x }.coerceAtLeast(0f) + 0.3f * span, ends.maxOf { it.y }.coerceAtLeast(0f) + 0.3f * span))
            pen.arrow(Vec2(lo.x, 0f), Vec2(hi.x, 0f), Role.Ink)
            pen.arrow(Vec2(0f, lo.y), Vec2(0f, hi.y), Role.Ink)
            pen.label("x", Vec2(hi.x, 0f), Vec2(0.3f, -1f), gap = 2f)
            pen.label("y", Vec2(0f, hi.y), Vec2(1f, 0.3f), gap = 2f)
        }
        for ((name, a, b) in vs) {
            pen.arrow(m(a), m(b))
            pen.label(name, mid(m(a), m(b)), unit(m(b) - m(a)).rot90(), gap = 2f, role = Role.Vector)
            if (p.flag("comp", "components") && a.x == 0f && a.y == 0f) {
                pen.line(m(b), m(Vec2(b.x, 0f)), Role.Note, dashed = true)
                pen.line(m(b), m(Vec2(0f, b.y)), Role.Note, dashed = true)
            }
        }
        if (sum != null) {
            val from = vs.first().second
            val to = vs.last().third
            pen.arrow(m(from), m(to), Role.Accent)
            pen.label(sum, mid(m(from), m(to)), unit(m(to) - m(from)).rot90() * -1f, gap = 2f, role = Role.Accent)
        }
        for (r in p.all("ang", "angle")) {
            val text = Params.textOf(r.getOrNull(0)) ?: continue
            val i = ((Params.numOf(r.getOrNull(1)) ?: 1f).toInt() - 1).coerceIn(0, vs.lastIndex)
            val (_, a, b) = vs[i]
            pen.angle(m(a), 0f, angleOf(b - a), r = 16f, label = text)
        }
        return true
    }

    @Suppress("unused") private fun tanDeg(d: Float) = tan(Math.toRadians(d.toDouble())).toFloat()
}

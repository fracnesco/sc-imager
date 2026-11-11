// =====================================================================
// ImagerView - Imager's display: the bands' widths, and the image itself
//
// Left, the band plane: frequency along (log), each band a column between its
// splits, in its colour. Up from the middle line is wider, down is narrower,
// to mono at the bottom; the bar is the band's width, the apricot cap over it
// how much side stereoize adds. Under them each band's correlation runs by,
// the last five seconds of it: +1 is mono, 0 two unrelated sides, under 0 the
// sides fight and the band thins out in mono.
//
//   click a band               show its controls (focusAction)
//   drag a band up and down    its width (shift: fine)
//   the wheel on a band        its stereoize
//   drag a split line          move the split
//   double click               back to the default of what is under it
//
// Right, the image: the output as dots, middle up and side across, so a mono
// sound is a vertical line, left at the upper left and right at the upper
// right. Three ways of drawing it, chosen by the words over it: dots (polar,
// folded into a half circle - the 45 degree lines are hard left and right,
// past them the sides are out of phase), lissajous (the dots as they come) and
// level (the loudest dot in each direction, as rays). Under it, the
// correlation of the whole output.
//
// The bars are the settings; the correlations, the dots and the bar under the
// image are what the server measures, and they move only while the window is
// running.
// =====================================================================

ImagerView : FxParamView {
	classvar <>historySize = 125;		// correlation values kept per band: 5 s at 25 a second
	classvar <>trail = 6;				// meter replies of dots kept on screen

	var <focus = 0, <>focusAction;
	var <>scopeMode = \dots;
	var history, dots, rays, scopePeak = 0.05;
	var drag, dragY, dragFrom, dragShift = false;

	*minHeight { ^230 }

	*scopeModes { ^#[\dots, \lissajous, \level] }

	init { |argUnit, parent|
		history = { [] } ! 4;
		dots = [];
		rays = 0 ! 37;
		^super.init(argUnit, parent)
	}

	focus_ { |i| focus = i; this.refresh }

	// ------------------------------------------------------------- geometry

	prScopeSide { ^(bh - 84).min(bw * 0.34).max(60) }

	prScopeRect {
		var side = this.prScopeSide;
		^Rect(bw - side - 8, 40, side, side)
	}

	prPlaneRect { ^Rect(8, 24, this.prScopeRect.left - 24, bh - 32) }

	// the bars' part of the plane, and the correlations' part under it
	prBarRect { |r| r = r ? this.prPlaneRect; ^Rect(r.left, r.top + 14, r.width, (r.height - 14) * 0.66) }
	prCorrRect { |r|
		var b;
		r = r ? this.prPlaneRect;
		b = this.prBarRect(r);
		^Rect(r.left, b.bottom + 8, r.width, r.bottom - b.bottom - 22)
	}

	prFreqX { |f, r| ^r.left + FxParamView.freqToX(f, r.width) }
	prXFreq { |x, r| ^FxParamView.xToFreq(x - r.left, r.width) }

	prNumBands { ^Imager.numBands(unit) }

	// the bands as shown: [lo, hi] in Hz each
	prBandEdges {
		var nb = this.prNumBands, f = Imager.splits(unit);
		^nb.collect { |d|
			[if (d == 0) { 20 } { f[d - 1] }, if (d == (nb - 1)) { 20000 } { f[d] }]
		}
	}

	prWidthY { |w, r| ^r.center.y - (w.clip(-1, 1) * r.height * 0.5) }

	prBandAt { |x|
		var r = this.prPlaneRect, f;
		if (x < r.left or: { x > r.right }) { ^nil };
		f = this.prXFreq(x, r);
		this.prBandEdges.do { |e, d| if (f >= e[0] and: { f <= e[1] }) { ^d } };
		^nil
	}

	prSplitAt { |x, y|
		var r = this.prPlaneRect, nb = this.prNumBands, f = Imager.splits(unit);
		if (r.containsPoint(x @ y).not) { ^nil };
		(nb - 1).do { |k|
			if ((this.prFreqX(f[k], r) - x).abs < 5) { ^k }
		};
		^nil
	}

	prKey { |name, d| ^(name ++ (d + 1)).asSymbol }

	// ----------------------------------------------------------- what came in

	// a displayed band's energies [mid in, side in, mid out, side out]: the
	// sub-bands past the count belong to the last band
	prEnergies { |d|
		var ex = unit.extras ? [], nb = this.prNumBands, e = 0 ! 4;
		if (ex.size < 16) { ^nil };
		4.do { |i| if (i.min(nb - 1) == d) { 4.do { |j| e[j] = e[j] + (ex[i * 4 + j] ? 0) } } };
		^e
	}

	levelsArrived {
		var ex = unit !? { unit.extras } ? [], n = Imager.scopePoints, nb, pts;
		if (ex.size < Imager.extrasSize) { ^this };
		nb = this.prNumBands;
		4.do { |d|
			var e = if (d < nb) { this.prEnergies(d) };
			var c = e !? { Imager.correlation(e[2], e[3]) };
			if (e.notNil and: { (e[2] + e[3]) < 1e-9 }) { c = nil };
			history[d] = (history[d] ++ [c]).keep(historySize.neg);
		};
		pts = n.collect { |j|
			var l = ex[18 + j], r = ex[18 + n + j];
			[(r - l) * 0.7071, (l + r) * 0.7071]
		};
		scopePeak = max(scopePeak * 0.97, pts.collect { |p| p[0].hypot(p[1]) }.maxItem ? 0).max(0.003);
		dots = (dots ++ [pts]).keep(trail.neg);
		// level: the loudest dot in each of 37 directions, falling back slowly
		rays = rays * 0.9;
		pts.do { |p|
			var q = this.class.fold(p), a = atan2(q[0], q[1]), k = a.linlin(-pi / 2, pi / 2, 0, 36).round.asInteger;
			rays[k] = rays[k].max(q[0].hypot(q[1]));
		};
	}

	// a dot folded into the upper half: a side under the middle is the same
	// direction, upside down
	*fold { |p| ^if (p[1] < 0) { [p[0].neg, p[1].neg] } { p } }

	// ---------------------------------------------------------------- drawing

	prDraw {
		var r = this.prPlaneRect, edges = this.prBandEdges, f = Imager.splits(unit), nb = edges.size;
		var caption = "% band%".format(nb, if (nb > 1) { "s" } { "" });
		(nb - 1).do { |k| caption = caption ++ " · " ++ FxPalette.format(f[k], "Hz", nil, 0) };
		this.prCaption(caption, 8, 4, FxPalette.text);
		this.prDrawBars(r, edges, f);
		this.prDrawCorrelations(r, edges);
		this.prDrawScope;
	}

	prAnySolo {
		^this.prNumBands.collect { |d| unit.get(this.prKey("solo", d)) == true }.includes(true)
	}

	prDrawBars { |r, edges, f|
		var b = this.prBarRect(r), anySolo = this.prAnySolo, ident = Imager.identityColour;
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(b, 3, 3);
		Pen.fill;
		// the scale: wider up, mono down, as it came in the middle
		[-1, -0.5, 0, 0.5, 1].do { |w|
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (w == 0) { 0.9 } { 0.3 });
			Pen.width = 1;
			Pen.line(b.left @ this.prWidthY(w, b), b.right @ this.prWidthY(w, b));
			Pen.stroke;
		};
		[50, 100, 200, 500, 1000, 2000, 5000, 10000].do { |hz|
			var x = this.prFreqX(hz, b);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.25);
			Pen.line(x @ b.top, x @ b.bottom);
			Pen.stroke;
			Pen.stringCenteredIn(if (hz >= 1000) { (hz div: 1000).asString ++ "k" } { hz.asString },
				Rect(x - 16, r.bottom - 12, 32, 12), FxPalette.smallFont, FxPalette.dim);
		};
		Pen.stringLeftJustIn("wider", Rect(b.left + 4, b.top + 2, 60, 12), FxPalette.smallFont, FxPalette.dim);
		Pen.stringLeftJustIn("mono", Rect(b.left + 4, b.bottom - 14, 60, 12), FxPalette.smallFont, FxPalette.dim);
		edges.do { |e, d|
			var x0 = this.prFreqX(e[0], b), x1 = this.prFreqX(e[1], b), col = Imager.bandColour(d);
			var w = unit.get(this.prKey("width", d)), a = unit.get(this.prKey("stereo", d));
			var solo = unit.get(this.prKey("solo", d)) == true, heard = anySolo.not or: { solo };
			var y0 = this.prWidthY(0, b), y1 = this.prWidthY(w, b), cap;
			var alpha = if (heard) { 1 } { 0.35 };
			Pen.fillColor = FxPalette.fade(col, 0.28 * alpha);
			Pen.fillRect(Rect(x0 + 2, y0.min(y1), x1 - x0 - 4, (y1 - y0).abs.max(1)));
			// stereoize: a cap of new side over the bar
			if (a > 0) {
				cap = a * b.height * 0.18;
				Pen.fillColor = FxPalette.fade(ident, 0.35 * alpha);
				Pen.fillRect(Rect(x0 + 2, y1.min(y0) - cap, x1 - x0 - 4, cap));
			};
			Pen.strokeColor = FxPalette.fade(col, alpha);
			Pen.width = 2;
			Pen.line((x0 + 2) @ y1, (x1 - 2) @ y1);
			Pen.stroke;
			if (d == focus) {
				Pen.strokeColor = FxPalette.fade(col, 0.8);
				Pen.width = 1.5;
				Pen.addRoundedRect(Rect(x0 + 1, b.top + 1, x1 - x0 - 2, b.height - 2), 2, 2);
				Pen.stroke;
			};
			Pen.stringCenteredIn(FxPalette.format(w, "", \pct, 0, true),
				Rect(x0, (y1 - 16).clip(b.top + 14, b.bottom - 14), x1 - x0, 12), FxPalette.smallFont,
				FxPalette.fade(col, alpha));
			if (solo) {
				Pen.stringCenteredIn("solo", Rect(x0, b.top + 2, x1 - x0, 12), FxPalette.smallFont, FxPalette.accent);
			};
		};
		// the splits, as lines to drag
		(edges.size - 1).do { |k|
			var x = this.prFreqX(f[k], b);
			Pen.strokeColor = FxPalette.handle;
			Pen.width = 1.5;
			Pen.line(x @ (b.top - 10), x @ (this.prCorrRect(r).bottom));
			Pen.stroke;
			Pen.fillColor = FxPalette.handle;
			Pen.fillRect(Rect(x - 3, b.top - 12, 6, 8));
		};
	}

	prDrawCorrelations { |r, edges|
		var c = this.prCorrRect(r);
		var yOf = { |v| c.center.y - (v.clip(-1, 1) * c.height * 0.5) };
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(c, 3, 3);
		Pen.fill;
		[-1, 0, 1].do { |v|
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, if (v == 0) { 0.8 } { 0.3 });
			Pen.width = 1;
			Pen.line(c.left @ yOf.(v), c.right @ yOf.(v));
			Pen.stroke;
		};
		Pen.stringLeftJustIn("+1", Rect(c.left + 3, c.top, 20, 10), FxPalette.smallFont, FxPalette.dim);
		Pen.stringLeftJustIn("-1", Rect(c.left + 3, c.bottom - 11, 20, 10), FxPalette.smallFont, FxPalette.dim);
		edges.do { |e, d|
			var x0 = this.prFreqX(e[0], c) + 4, x1 = this.prFreqX(e[1], c) - 4;
			var h = history[d], col = Imager.bandColour(d), first = true, en, cin;
			if (h.size > 1) {
				Pen.strokeColor = col;
				Pen.width = 1.5;
				h.do { |v, j|
					var x = x1 - ((h.size - 1 - j) / (historySize - 1) * (x1 - x0));
					if (v.isNil) { first = true } {
						if (first) { Pen.moveTo(x @ yOf.(v)); first = false } { Pen.lineTo(x @ yOf.(v)) };
					};
				};
				Pen.stroke;
			};
			// now: out as a number, in as a dim tick at the right edge
			en = this.prEnergies(d);
			if (en.notNil and: { (en[0] + en[1]) > 1e-9 }) {
				cin = Imager.correlation(en[0], en[1]);
				Pen.fillColor = FxPalette.dim;
				Pen.fillRect(Rect(x1 - 6, yOf.(cin) - 1, 6, 2));
			};
			h.last !? { |v|
				Pen.stringRightJustIn(FxPalette.fixed(v, 2), Rect(x0, c.top + 1, x1 - x0, 11),
					FxPalette.smallFont, col);
			};
		};
		Pen.stringLeftJustIn("correlation", Rect(c.left + 24, c.top, 80, 10), FxPalette.smallFont, FxPalette.dim);
	}

	prDrawScope {
		var s = this.prScopeRect, cx = s.center.x, ident = Imager.identityColour;
		var polar = scopeMode != \lissajous;
		var o = if (polar) { cx @ (s.bottom - 6) } { s.center };
		var rad = if (polar) { (s.width * 0.5 - 6).min(s.height - 12) } { s.width * 0.5 - 6 };
		var toPt = { |p| (o.x + (p[0] / scopePeak * rad * 0.9)) @ (o.y - (p[1] / scopePeak * rad * 0.9)) };
		var x = s.left, ex = unit.extras ? [], corr;
		// the words that choose the drawing
		this.class.scopeModes.do { |m, i|
			var w = s.width / 3;
			Pen.stringCenteredIn(m.asString, Rect(s.left + (i * w), s.top - 16, w, 12), FxPalette.smallFont,
				if (m == scopeMode) { FxPalette.text } { FxPalette.dim });
		};
		this.prCaption("image", s.left, 4, FxPalette.text);
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(s, 3, 3);
		Pen.fill;
		// the guides: mono up, hard left and right at 45 degrees, side across
		Pen.width = 1;
		Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.6);
		if (polar) {
			Pen.addArc(o, rad, pi, pi);
			Pen.stroke;
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.35);
			Pen.addArc(o, rad * 0.5, pi, pi);
			Pen.stroke;
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.6);
			Pen.line(o, o.x @ (o.y - rad));
			Pen.line(o, (o.x - (rad * 0.7071)) @ (o.y - (rad * 0.7071)));
			Pen.line(o, (o.x + (rad * 0.7071)) @ (o.y - (rad * 0.7071)));
			Pen.line((o.x - rad) @ o.y, (o.x + rad) @ o.y);
			Pen.stroke;
			Pen.stringCenteredIn("out of phase", Rect(s.left + 2, o.y - 14, 60, 10), FxPalette.smallFont,
				FxPalette.fade(FxPalette.dim, 0.7));
		} {
			Pen.line(o.x @ s.top, o.x @ s.bottom);
			Pen.line(s.left @ o.y, s.right @ o.y);
			Pen.strokeColor = FxPalette.fade(FxPalette.waveDim, 0.35);
			Pen.line(s.leftTop, s.rightBottom);
			Pen.line(s.rightTop, s.leftBottom);
			Pen.stroke;
		};
		Pen.stringLeftJustIn("L", Rect(s.left + 4, s.top + 3, 10, 10), FxPalette.smallFont, FxPalette.dim);
		Pen.stringRightJustIn("R", Rect(s.right - 14, s.top + 3, 10, 10), FxPalette.smallFont, FxPalette.dim);
		Pen.stringCenteredIn("M", Rect(cx - 5, s.top + 3, 10, 10), FxPalette.smallFont, FxPalette.dim);
		switch (scopeMode,
			\level, {
				var n = rays.size;
				Pen.strokeColor = FxPalette.fade(ident, 0.85);
				Pen.width = 2;
				rays.do { |v, k|
					var a = k.linlin(0, n - 1, pi / -2, pi / 2);
					var len = (v / scopePeak).clip(0, 1.1) * rad * 0.9;
					if (len > 0.5) { Pen.line(o, (o.x + (a.sin * len)) @ (o.y - (a.cos * len))) };
				};
				Pen.stroke;
			},
			{
				dots.do { |pts, age|
					Pen.fillColor = FxPalette.fade(ident, (age + 1) / dots.size * 0.8);
					pts.do { |p|
						var q = if (polar) { this.class.fold(p) } { p };
						var pt = toPt.(q);
						if (s.containsPoint(pt)) { Pen.fillRect(Rect(pt.x - 1, pt.y - 1, 2.5, 2.5)) };
					};
				};
			}
		);
		// the whole output's correlation, as a bar from -1 to +1
		if (ex.size >= 18 and: { (ex[16] + ex[17]) > 1e-9 }) { corr = Imager.correlation(ex[16], ex[17]) };
		this.prCorrBar(Rect(s.left, s.bottom + 8, s.width, 10), corr);
	}

	prCorrBar { |r, corr|
		Pen.fillColor = FxPalette.bg;
		Pen.addRoundedRect(r, 2, 2);
		Pen.fill;
		Pen.fillColor = FxPalette.fade(FxPalette.waveDim, 0.8);
		Pen.fillRect(Rect(r.center.x, r.top, 1, r.height));
		corr !? {
			var x = r.left + ((corr.clip(-1, 1) + 1) * 0.5 * r.width);
			Pen.fillColor = FxPalette.fade(Imager.identityColour, 0.5);
			Pen.fillRect(Rect(r.center.x.min(x), r.top + 2, (x - r.center.x).abs, r.height - 4));
			Pen.fillColor = FxPalette.handle;
			Pen.fillRect(Rect(x - 1, r.top, 2, r.height));
		};
		Pen.stringLeftJustIn("-1", Rect(r.left, r.bottom + 1, 20, 10), FxPalette.smallFont, FxPalette.dim);
		Pen.stringCenteredIn(corr !? { "correlation " ++ FxPalette.fixed(corr, 2) } ? "correlation",
			Rect(r.left, r.bottom + 1, r.width, 10), FxPalette.smallFont, FxPalette.dim);
		Pen.stringRightJustIn("+1", Rect(r.right - 20, r.bottom + 1, 20, 10), FxPalette.smallFont, FxPalette.dim);
	}

	// --------------------------------------------------------------- playing

	prMouseDown { |x, y, mod, count|
		var s = this.prScopeRect, k, d, key;
		dragShift = mod.isShift;
		drag = nil;
		// the words over the image
		if (Rect(s.left, s.top - 18, s.width, 16).containsPoint(x @ y)) {
			scopeMode = this.class.scopeModes[((x - s.left) / s.width * 3).floor.asInteger.clip(0, 2)];
			this.refresh;
			^this
		};
		k = this.prSplitAt(x, y);
		if (k.notNil) {
			key = ("split" ++ (k + 1)).asSymbol;
			if (count == 2) { unit.set(key, Imager.param(key)[\default]); ^this };
			drag = key;
			^this
		};
		d = this.prBandAt(x);
		if (d.notNil and: { this.prPlaneRect.containsPoint(x @ y) }) {
			if (focus != d) { focus = d; focusAction.value(d); this.refresh };
			key = this.prKey("width", d);
			if (count == 2) { unit.set(key, 0); ^this };
			drag = key;
			dragFrom = unit.get(key);
			dragY = y;
		};
	}

	prMouseMove { |x, y, mod|
		var b;
		if (drag.isNil) { ^this };
		if (drag.asString.beginsWith("split")) {
			unit.set(drag, this.prXFreq(x, this.prPlaneRect).clip(20, 18000));
			^this
		};
		if (mod.isShift != dragShift) { dragShift = mod.isShift; dragFrom = unit.get(drag); dragY = y };
		b = this.prBarRect;
		unit.set(drag, dragFrom + ((dragY - y) / (b.height * 0.5) * if (dragShift) { 0.1 } { 1 }));
	}

	prMouseUp { drag = nil }

	prWheel { |x, y, delta|
		var d = this.prBandAt(x), key;
		if (d.isNil or: { this.prPlaneRect.containsPoint(x @ y).not }) { ^this };
		key = this.prKey("stereo", d);
		unit.set(key, (unit.get(key) + (delta.sign * 0.05)).clip(0, 1));
	}

	hint { |x, y|
		var s = this.prScopeRect, d, k;
		if (x.isNil) { ^"" };
		if (Rect(s.left, s.top - 18, s.width, 16).containsPoint(x @ y)) {
			^"how the image is drawn: dots (folded into a half circle), lissajous (as they come) or level (the loudest in each direction)"
		};
		if (s.insetBy(-4, -4).containsPoint(x @ y)) {
			^"the output's image: middle up, side across - a mono sound is a vertical line, past the 45 degree lines the sides are out of phase"
		};
		if (y > (s.bottom + 4) and: { x > s.left }) {
			^"the output's correlation: +1 is mono, 0 two unrelated sides, under 0 the sides cancel in mono"
		};
		k = this.prSplitAt(x, y);
		if (k.notNil) { ^"split % - drag it along; double click for its default".format(k + 1) };
		d = this.prBandAt(x);
		if (d.isNil) { ^"" };
		if (y > this.prCorrRect.top) {
			^"band %: its correlation, the last five seconds - the dim tick is what came in".format(d + 1)
		};
		^"band %: drag up and down for its width (shift: fine), the wheel for stereoize, double click for as it came in".format(d + 1)
	}
}

// =====================================================================
// Imager - a stereo imager, in up to four bands
//
// A stereo signal is a middle and a side: what both channels share, and what
// differs between them. Making a sound wider or narrower is turning the side
// up or down, and doing it in bands is what keeps the bass in the middle while
// the top opens out. Each band has a `width` (-100 % is mono, 0 as it came in,
// +100 % the side doubled) and can be `stereo`ized: given a side it did not
// have, made from its own middle - a short delay (`mode` delay, colourful) or
// a chain of allpasses (`mode` diffuse, smoother). Both only ever write the
// side, so the mono sum (left + right) never changes. Narrowing throws side
// away; `recover` puts what a band lost back into the middle, so a narrowed
// bass keeps its weight.
//
//   Ndef(\pad, { Imager.ar(Pan2.ar(Saw.ar([110, 165]).sum * 0.1, LFNoise1.kr(0.3)), \imager) }).play;
//   p = Imager(Ndef(\pad), \imager);
//   p.gui;
//   p.bands = '3'; p.split1 = 150; p.split2 = 3000;
//   p.width1 = -1;                       // the bass in the middle
//   p.width3 = 0.5; p.stereo3 = 0.4;     // the top wider, and given more side
//   p.preset = 'mono bass';
//
// The bands are split with Linkwitz-Riley crossovers, which add up flat but
// shift the phase around each split, as every multiband effect without
// look-ahead does. The dry side of `mix` is split and summed the same way, so
// mixing never combs; with one band nothing is split at all.
// =====================================================================

Imager : FxParamUnit {
	*controlName { ^\imager }
	*displayClass { ^ImagerView }
	*guiClass { ^FxDeviceGUI }
	*panelClass { ^FxDevicePanel }
	*windowSize { ^Point(980, 580) }
	*insertCategory { ^\utility }

	// the stereo family's apricot: the image, the side it adds
	*identityColour { ^FxPalette.identity(\stereo) ?? { Color(0.98, 0.78, 0.58) } }

	// a band's colour, low to high, as ParaEQ's handles read
	*bandColour { |i| ^FxPalette.eqHandle(i.asInteger.clip(0, 3)) }

	// how many points of the output a meter reply carries for the scope, per side
	*scopePoints { ^32 }

	// the extras: per band four energies (mid in, side in, mid out, side out),
	// then mid and side of the whole output, then the scope's left and right
	*extrasSize { ^16 + 2 + (this.scopePoints * 2) }

	*params {
		var band = { |i|
			var n = i + 1, s = ("band " ++ n).asSymbol;
			[
				this.knob(("width" ++ n).asSymbol, -1, 1, \lin, 0, "", "band " ++ n,
					"band %: -100 is mono, 0 as it came in, +100 the side doubled".format(n),
					box: \pct, section: s, size: \big),
				this.knob(("stereo" ++ n).asSymbol, 0, 1, \lin, 0, "", "stereoize",
					"band %: a side made from its own middle - for a sound that has too little to widen".format(n),
					box: \pct, section: s),
				this.menu(("mode" ++ n).asSymbol, #[\delay, \diffuse], \diffuse, "mode",
					"band %: how the side is made - a short delay (colourful, combs) or allpasses (smooth)".format(n), s),
				this.knob(("time" ++ n).asSymbol, 0.001, 0.04, \exp, 0.012, "s", "time",
					"band %: the delay's length, in delay mode".format(n), box: \ms, section: s, size: \small),
				this.toggle(("solo" ++ n).asSymbol, false, "solo",
					"band %: hear only the bands that are soloed".format(n), s)
			]
		};
		^[
			this.menu(\bands, #['1', '2', '3', '4'], '4', "bands",
				"how many bands: each split adds one", \bands),
			this.knob(\split1, 20, 18000, \exp, 150, "Hz", "split 1",
				"between band 1 and band 2", section: \bands, dec: 0, size: \small),
			this.knob(\split2, 20, 18000, \exp, 1500, "Hz", "split 2",
				"between band 2 and band 3", section: \bands, dec: 0, size: \small),
			this.knob(\split3, 20, 18000, \exp, 6000, "Hz", "split 3",
				"between band 3 and band 4", section: \bands, dec: 0, size: \small),
			4.collect(band),
			this.knob(\recover, 0, 1, \lin, 0, "", "recover",
				"what narrowing takes from the side, put back into the middle: a narrowed band keeps its weight",
				box: \pct, section: \output),
			this.mixParams(\output, 1)
		]
	}

	// one page: the splits, every band's width at once (as Ozone's faders), the
	// rest of the band shown (1 2 3 4), then the output card
	*devicePages {
		^[
			\imager -> [
				(title: "bands", keys: [\bands, [\split1, \split2], \split3]),
				(title: "width", keys: [\width1, \width2, \width3, \width4]),
				(title: "band", count: 4,
					keys: { |i| var n = i + 1; [
						("stereo" ++ n).asSymbol, [("mode" ++ n).asSymbol, ("time" ++ n).asSymbol],
						("solo" ++ n).asSymbol
					] },
					colour: { |i| Imager.bandColour(i) })
			]
		]
	}

	// each band's width knob wears the band's colour, as its column in the display
	*keyColour { |key|
		^if (key.asString.beginsWith("width")) { this.bandColour(this.bandOf(key)) }
	}

	*numBands { |unit| ^(#['1', '2', '3', '4'].indexOf(unit.get(\bands)) ? 3) + 1 }

	*bandOf { |key|
		var s = key.asString;
		^if (s.size > 1 and: { s.last.isDecDigit } and: { s.beginsWith("split").not }) { s.last.digit - 1 }
	}

	*isActive { |key, unit|
		var nb = this.numBands(unit), b = this.bandOf(key), s = key.asString, n;
		if (s.beginsWith("split")) { ^s.last.digit < nb };
		if (key == \recover) { ^nb.collect { |i| unit.get(("width" ++ (i + 1)).asSymbol) < 0 }.includes(true) };
		if (b.isNil) { ^true };
		if (b >= nb) { ^false };
		n = b + 1;
		if (s.beginsWith("mode")) { ^unit.get(("stereo" ++ n).asSymbol) > 0 };
		if (s.beginsWith("time")) {
			^(unit.get(("stereo" ++ n).asSymbol) > 0) and: { unit.get(("mode" ++ n).asSymbol) == \delay }
		};
		^true
	}

	// what a Tidal pattern may move through SuperDirtGUI, event by event: not
	// the band count (it switches the splitting) nor solo (a Tidal word)
	*tidalParams {
		^[\split1, \split2, \split3] ++ 4.collect { |i|
			var n = i + 1;
			[("width" ++ n).asSymbol, ("stereo" ++ n).asSymbol, ("mode" ++ n).asSymbol, ("time" ++ n).asSymbol]
		}.flat ++ [\recover, \mix]
	}

	*presetList {
		^[
			\default, (),
			'mono bass', (bands: '2', split1: 120, width1: -1, width2: 0),
			'mono check', (bands: '1', width1: -1),
			'wide top', (bands: '2', split1: 2500, width2: 0.6, stereo2: 0.3),
			'master glue', (bands: '4', split1: 150, split2: 1200, split3: 6000,
				width1: -1, width2: -0.1, width3: 0.15, width4: 0.3),
			'mono to stereo', (bands: '3', split1: 200, split2: 3000, width1: -1,
				stereo2: 0.5, width2: 0.3, stereo3: 0.7, width3: 0.5),
			'haas wide', (bands: '1', stereo1: 0.6, mode1: \delay, time1: 0.014),
			'narrow mids, keep power', (bands: '3', split1: 250, split2: 4000, width2: -0.7, recover: 0.6),
			'super wide', (bands: '4', width2: 0.4, width3: 0.8, width4: 1, stereo3: 0.3, stereo4: 0.4)
		]
	}

	// ------------------------------------------------------------ the model

	// the splits as the graph takes them: each at least 1.2 times the last
	*splitsOf { |s1, s2, s3|
		var f1 = s1.clip(20, 18000), f2, f3;
		f2 = s2.max(f1 * 1.2).clip(20, 19000);
		f3 = s3.max(f2 * 1.2).clip(20, 19500);
		^[f1, f2, f3]
	}

	*splits { |unit| ^this.splitsOf(unit.get(\split1), unit.get(\split2), unit.get(\split3)) }

	// the correlation of left and right from the energies of middle and side:
	// +1 is mono, 0 unrelated, -1 the sides opposite
	*correlation { |em, es| ^if ((em + es) > 1e-9) { (em - es) / (em + es) } { 1 } }

	// ------------------------------------------------------------- the DSP

	*prLp { |x, f| ^LPF.ar(LPF.ar(x, f), f) }
	*prHp { |x, f| ^HPF.ar(HPF.ar(x, f), f) }
	// a Linkwitz-Riley pair added back up: flat, only the phase turned
	*prAp { |x, f| ^this.prLp(x, f) + this.prHp(x, f) }

	// four bands that add up to the input, all through the same phase turns
	*prSplit { |x, f1, f2, f3|
		var lo = this.prLp(x, f1), hi = this.prHp(x, f1), rest = this.prHp(hi, f2);
		^[
			this.prAp(this.prAp(lo, f2), f3),
			this.prAp(this.prLp(hi, f2), f3),
			this.prLp(rest, f3),
			this.prHp(rest, f3)
		]
	}

	// a side made from a middle: a short delay, or allpasses (scaled shorter
	// for the higher bands), crossfaded by mode
	*prStereoize { |mid, mode, time, i|
		var delayed = DelayC.ar(mid, 0.05, time.clip(0.0005, 0.05));
		var scale = #[1, 0.8, 0.6, 0.45][i];
		var diffuse = #[0.0037, 0.0061, 0.0089, 0.0113].inject(mid, { |x, d|
			AllpassN.ar(x, 0.02, d * scale, 0.08)
		});
		^delayed + ((diffuse - delayed) * Lag.kr(mode, 0.05))
	}

	// one band's middle and side, imaged: [mid, side]
	*prBand { |mid, side, src, w, a, mode, time, recover, gate, i|
		var newSide = (side * (1 + w)) + (this.prStereoize(src, mode, time, i) * a);
		var newMid = mid + (side * w.neg.max(0) * recover);
		^[newMid * gate, newSide * gate]
	}

	*prPair { |l, r, p, wantExtras|
		var nb = p[\bands] + 1;
		var f = this.splitsOf(p[\split1], p[\split2], p[\split3]);
		var m = (l + r) * 0.5, s = (l - r) * 0.5;
		var mb = this.prSplit(m, *f), sb = this.prSplit(s, *f);
		// a band past the count takes the last band's settings: its part of the
		// spectrum belongs to that band
		var pick = { |name, i|
			Select.kr((nb - 1).min(i), 4.collect { |j| p[(name ++ (j + 1)).asSymbol] })
		};
		var solos = 4.collect { |i| pick.("solo", i) };
		var anySolo = (solos.sum > 0);
		var gates = solos.collect { |so| Lag.kr((so + (1 - anySolo)).clip(0, 1), 0.02) };
		var bands = 4.collect { |i|
			this.prBand(mb[i], sb[i], mb[i], pick.("width", i), pick.("stereo", i),
				pick.("mode", i), pick.("time", i), p[\recover], gates[i], i)
		};
		// one band: nothing split, nothing turned
		var one = this.prBand(m, s, m, p[\width1], p[\stereo1], p[\mode1], p[\time1],
			p[\recover], Lag.kr(((p[\solo1] + (1 - (p[\solo1] > 0)))).clip(0, 1), 0.02), 0);
		var k = Lag.kr(nb > 1, 0.05);
		var mOut = one[0] + ((bands.collect(_[0]).sum - one[0]) * k);
		var sOut = one[1] + ((bands.collect(_[1]).sum - one[1]) * k);
		var dm = m + ((mb.sum - m) * k), ds = s + ((sb.sum - s) * k);
		var out = [mOut + sOut, mOut - sOut], dry = [dm + ds, dm - ds];
		var extras;
		if (wantExtras) {
			extras = this.prExtras(mb, sb, bands, out);
		};
		^[out, dry, extras]
	}

	// what the display reads: energies per band, in and out, the whole
	// output's, and the scope - points caught at even steps through one
	// meter period, so a reply carries the last 40 ms of the output
	*prExtras { |mb, sb, bands, out|
		var energy = { |x| A2K.kr(Lag.ar(x.squared, 0.25)) };
		var per = 4.collect { |i|
			[energy.(mb[i]), energy.(sb[i]), energy.(bands[i][0]), energy.(bands[i][1])]
		}.flat;
		var mid = (out[0] + out[1]) * 0.5, side = (out[0] - out[1]) * 0.5;
		var n = this.scopePoints, rate = FxParamUnit.meterRate;
		var points = 2.collect { |c|
			n.collect { |j| A2K.kr(Latch.ar(out[c], Impulse.ar(rate, j / n))) }
		}.flat;
		^per ++ [energy.(mid), energy.(side)] ++ points
	}

	*process { |sig, p, ctx|
		var n = sig.size, outs = [], drys = [], extras;
		if (n < 2) {
			// one channel has no image: nothing to do
			ctx[\extras] = 0 ! this.extrasSize;
			^sig
		};
		(n div: 2).do { |k|
			var r = this.prPair(sig[k * 2], sig[k * 2 + 1], p, k == 0);
			outs = outs ++ r[0];
			drys = drys ++ r[1];
			if (k == 0) { extras = r[2] };
		};
		if (n.odd) { outs = outs.add(sig.last); drys = drys.add(sig.last) };
		ctx[\dry] = drys;
		ctx[\extras] = extras;
		^outs
	}
}

// Imager - a walkthrough. Evaluate one block at a time (cmd/ctrl + return
// inside the parentheses), never the whole file.

// ---- 1. a stereo sound to work on
(
s.waitForBoot {
	Ndef(\pad, {
		var notes = [45, 52, 57, 64, 69].midicps;
		var voices = notes.collect { |f, i| Pan2.ar(Saw.ar(f * [1, 1.003]).sum * 0.03, LFNoise1.kr(0.2 + (i * 0.05))) };
		var kick = SinOsc.ar(EnvGen.ar(Env.perc(0.001, 0.3), Impulse.ar(2), 120, 45)) * Decay2.ar(Impulse.ar(2), 0.001, 0.4) * 0.5;
		Imager.ar(voices.sum + Pan2.ar(kick, 0.4), \imager)
	}).play;
};
)

// ---- 2. the object, and its window
p = Imager(Ndef(\pad), \imager);
p.gui;

// ---- 3. width: the side turned up and down
p.bands = '1';
p.width1 = -1;      // mono: the image is a vertical line, correlation +1
p.width1 = 0;       // as it came in
p.width1 = 1;       // the side doubled

// ---- 4. bands: the bass in the middle, the top wider
(
p.bands = '3';
p.split1 = 150; p.split2 = 3000;
p.width1 = -1; p.width2 = 0; p.width3 = 0.6;
)

// ---- 5. stereoize: a side the sound did not have
(
p.stereo2 = 0.5; p.mode2 = \diffuse;
p.stereo3 = 0.5; p.mode3 = \delay; p.time3 = 0.012;
);
p.solo3 = true;     // only band 3: hear what delay mode does
p.solo3 = false;

// ---- 6. recover: narrowing keeps its weight
p.recover = 0.6;

// ---- 7. presets
Imager.presetNames;
p.preset = 'master glue';
p.preset = 'mono to stereo';

// ---- 8. done
p.free; Ndef(\pad).clear;

---
name: railix-audio
description: "Compose Railix music, design state/interaction sounds, and implement MML synthesis, playback, mixing and audio performance. Not for asset discovery or file-editor persistence."
---

# Music And Sound Design

Create original, memorable cyber-factory audio that supports concentration. Industrial
and electronic RTS energy is inspiration, not permission to copy melodies or samples.

## Compose Music

1. Choose the track's mood, pulse and tonal center before writing notes. Build one
   convincing phrase first; do not stretch an unfinished loop to meet a duration.
2. For rhythmic tracks, establish drums and bass together. Give kick, snare and hats
   distinct jobs, leave space between accents, and lock the bass to the groove without
   duplicating every hit. Use explicit durations for syncopation or swing.
3. Write a short, singable motif with a deliberate contour, rests and an answering
   phrase. Support it with a coherent chord progression; make leaps and resolution
   intentional. Do not substitute random notes or a drone for requested melodic music.
4. Shape timbre with envelopes and filtering before adding drive, detuning or echo.
   Contrast lead, bass and accompaniment by register, rhythm and tone. Add a counterline
   only if it contributes; not every instrument must play throughout the track.
5. Develop sections: introduce the groove, state the theme, vary it, reduce density,
   return with purpose, and end cleanly. Align instrument sections with rests, plan
   tails, and vary instrumentation instead of repeating one full mix indefinitely.
6. Compare new tracks by rhythmic phrasing and melodic interval contour, not just
   absolute pitches or speed. Give each a distinct hook and arrangement. Humorous
   developer titles can suggest a mood; do not insert copyrighted quotes or samples.

## Design Effects

Use short cues with distinct envelopes and rhythms: a dry interaction click, a small
mechanical working gesture, a soft idle response, and distinguishable warning/error
cues. Unreached is neutral, not another alarm. Group cues may share the family while
using a different cadence. Do not encode every state only as a louder version.

Keep proximity ambience subordinate to feedback and music. Preserve bounded effect
sessions under rapid clicking; do not stack a source per Step, request or frame.
Use the existing state/event mapping, respect mute, and avoid repetitive alarm loops.

## Author And Synthesize

Write editable MML, not executable source or distributed binary audio. Use `name:`
and `tempo:` headers, then 1-8 parallel lines of
`instrument volume attack release | notes`; optional `key=value` controls go before `|`.
Tempo is 40-240 BPM; volume is 0-1 and attack/release are seconds.
Keep files within the 64 KiB limit.
Use whitespace-separated tokens: `o4` sets octave, `c@1` lasts one beat, `r@.5` rests,
`[ceg]@4` plays a chord, and `/: c@1 r@1 :/4` repeats. Accidentals use `+` or `-`.
Repeats allow 1-16 passes with at most three nested levels; chords have at most four notes.

Available instruments are sine, triangle, square, sawtooth, kick, snare and hat.
Use the supported decay, sustain, cutoff, detune, drive, pan and echo controls;
do not invent instrument names or syntax. Keep one validated score model and native
Web Audio synthesis path for playback and preview. Audio belongs only to Creator.
Schedule against its audio clock with bounded lookahead, reusable voices and shared
noise/routing. Do not expand long repeats or schedule an entire soundtrack at once.

## Mix And Verify

Balance parts before adjusting the master. Leave headroom for chords, effects and
echo tails; distinguish spectral roles rather than making every part louder. Compare
perceived loudness between tracks so shuffle does not jump in volume. Keep effects
audible without burying the music. Do not hide attenuation behind a 100% slider.

Render through the actual playback mixer at default and full user volume. Check
finite samples, peaks/RMS, clipping, phrase alignment, endings and sustained playback
across scheduler refills. Inspect full tracks, not only an introductory excerpt.
Audition melody, groove, fatigue and cue recognition, including rapid interactions;
numbers cannot establish musical quality. Report explicitly when listening was unavailable.

Test 0-100% gain, independent music/effect mute, pause/resume, stop, Next, shuffle
without immediate repeats when alternatives exist, and hidden-page cleanup. Measure
voice/context counts, CPU and memory during long playback and rapid actions.
Use Java 25 and existing Java Playwright tests with Chrome; run `./mvnw clean verify`
from repository root before publishing audio changes. Review WAVs may live under
`target/`, never in distributed assets. Stop task-owned renders, contexts and helpers.

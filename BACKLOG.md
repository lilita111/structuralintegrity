# Backlog

## End of 0.5

Three explosion effects, and the design bar for them: one algorithm that covers
all three cases, usually with just differing parameters, instead of a different
algorithm for each - the way a known, proven mathematical formula covers many
situations by parameterisation. `Integrity.chain(delta)` is the pattern: place,
break, and shockwave are one function with a different integer.

1. **Shockwave** (shipped in 0.5.9-gamma): each removed block's chain relaxes
   by `explosionShockwaveDelta` (default +8) instead of break's +1. Already a
   parameter of `chain()`.
2. **Explosive force on sable sub-levels**: TNT should apply a physics impulse
   to floating sable assemblies caught in the blast, so a detached wall is
   thrown, not just released. Separate mechanism from the shockwave.
3. **Mountain / stone splintering**: separate from both the shockwave and the
   explosive force.

The open design question is the shared formula the three parameterise - the
candidates should be evaluated against blast strength and distance the way
`chain()` was evaluated against sign.

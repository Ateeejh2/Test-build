# Attack compile fix

Minecraft 1.8.9 exposes the mapped left-click handler `func_147116_af` as a private method.
The mod now uses an Access Transformer at `src/main/resources/META-INF/wanderbot_at.cfg` to make that method public during ForgeGradle's deobfuscated compile/reobfuscation pipeline.

No gameplay logic was changed in this fix beyond making the existing `MovementController.clickAttack()` call compilable.

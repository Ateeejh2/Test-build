# Diamond target exclusion hotfix

Diamond armor is now a hard exclusion for target acquisition and search navigation.

- A player wearing at least one diamond armor piece is never selected as a combat target.
- SEARCH/HUNT navigation does not path toward diamond-armored players.
- If the current target equips diamond armor, `isViable()` drops the target on the next validation.
- Combat reconstruction will not propose a diamond-armored player as a retarget alternative.
- Diamond-armored players are still visible to generic threat/pressure models so they can influence safety decisions without being attacked.
- Iron/chain remain preferred, while no-armor and other non-diamond armor players remain eligible.

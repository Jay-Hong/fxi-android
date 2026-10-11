package com.jay.fxi.budget

/**
 * S4 CUT-C02 (b′) (cut_c02_bprime_agreed.r1.md): the JUnit category of the cutover harness's measurement rows. Only
 * `testC02Measurement` runs them and `testCutoverAcceptance` excludes them, so the cutover's case list is unchanged; their own
 * list is `tools/contracts/c02_measurement_cases.json`. A row over the budget fails as it is.
 */
interface C02Measurement

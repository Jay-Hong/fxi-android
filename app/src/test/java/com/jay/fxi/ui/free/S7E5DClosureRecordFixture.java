package com.jay.fxi.ui.free;

/**
 * S7 E5d gap hunt r1 G13·G14: a record's equals, hashCode and toString are an invokedynamic through
 * java.lang.runtime.ObjectMethods, a bootstrap the closure does not know and must report. Test code only.
 */
record S7E5DClosureRecordFixture(int value) {
}

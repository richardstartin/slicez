package io.github.richardstartin.slicez;

public interface BlockIterator {

	/**
	 * Get the bits after calling {@code nextBlock}.
	 *
	 * @return the bits for the current block
	 */
	Bits getBits();

	/**
	 * Decodes the next block into {@code bits} which can be retrieved via
	 * {@code getBits}. The {@code empty} flag is not maintained (these operations
	 * never yield empty blocks), but {@code bits} is marked {@link Bits#isFull()
	 * full} when the decoded block is full.
	 *
	 * @return the block id (i.e. the high bits of the values stored shifted right)
	 */
	int nextBlock();

	/**
	 * Whether any blocks remain
	 */
	boolean hasNext();

	/**
	 * A trivial filter doesn't actually filter
	 */
	default boolean isTrivial() {
		return false;
	}
}

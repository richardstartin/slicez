package io.github.richardstartin.slicez;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;

import static io.github.richardstartin.slicez.SliceZ.*;

public class CompressedBitmap {

	private static final int COOKIE = 0xFEDC1234;

	static final int HEADER_SIZE = Integer.SIZE + Integer.SIZE; // cookie + data offset
	static final int DATA_OFFSET = Integer.BYTES;
	static final int SHIFT = Long.bitCount(BLOCK_SIZE - 1);

	static final int SPARSE = 0;
	static final int SPARSE_INVERTED = 1;
	static final int DENSE = 2;
	static final int ABSENT = 3;

	public static final class Appender {

		private int previousBlock = -1;
		private long typesHigh;
		private long typesLow;
		private ByteBuffer metadata = ByteBuffer.allocate(512).position(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		private ByteBuffer blocks = ByteBuffer.allocate(BLOCK_WORDS).position(0).order(ByteOrder.LITTLE_ENDIAN);
		private final long[] bitmap = new long[BLOCK_WORDS];

		public void add(int rid) {
			int block = rid >>> SHIFT;
			if (block > previousBlock) {
				flush(block);
			}
			bitmap[(rid & (BLOCK_SIZE - 1)) >>> 6] |= (1L << rid);
		}

		private void flush(int block) {
			if (previousBlock >= 0) {
				appendBlock(previousBlock);
			}
			fillGap(block);
			Arrays.fill(bitmap, 0L);
		}

		private void fillGap(int block) {
			int b = previousBlock + 1; // first ABSENT block, if any
			while (b < block) {
				if ((b & 63) == 0 && b != 0) {
					// crossed into a new group: the word we just finished is complete
					writeTypes();
					typesHigh = 0L;
					typesLow = 0L;
				}
				// fill ABSENT bits up to either `block` or the next 64-block boundary
				int groupEnd = (b & ~63) + 64;
				int end = Math.min(block, groupEnd);
				int posInWord = b & 63;
				int count = end - b;
				long mask = count == 64 ? -1L : (((1L << count) - 1) << posInWord);
				typesHigh |= mask; // ABSENT high bit
				typesLow |= mask; // ABSENT low bit
				b = end;
			}
			if (block > 0 && (block & 63) == 0) {
				// `block` opens a fresh group, so the word holding the preceding blocks
				// (the last gap blocks and/or previousBlock) is now complete
				writeTypes();
				typesHigh = 0L;
				typesLow = 0L;
			}
			previousBlock = block;
		}

		private void writeTypes() {
			ensureMetadataCapacity(2 * Long.BYTES);
			metadata.putLong(typesHigh);
			metadata.putLong(typesLow);
		}

		private void appendBlock(int block) {
			int cardinality = cardinality();
			int type;
			if (cardinality < SPARSE_THRESHOLD) {
				type = SPARSE;
				writeSparse(cardinality, false);
			} else if (cardinality >= BLOCK_SIZE - SPARSE_THRESHOLD) {
				type = SPARSE_INVERTED;
				writeSparse(BLOCK_SIZE - cardinality, true);
			} else {
				type = DENSE;
				writeDense();
			}
			typesHigh |= ((type >>> 1) & 1L) << block;
			typesLow |= (type & 1L) << block;
		}

		private void writeSparse(int cardinality, boolean inverted) {
			ensureBlockCapacity((cardinality + 1) * Character.BYTES);
			blocks.putChar((char) cardinality);
			for (int i = 0; i < bitmap.length; i++) {
				long word = inverted ? ~bitmap[i] : bitmap[i];
				while (word != 0) {
					int bit = Long.numberOfTrailingZeros(word);
					word &= (word - 1);
					blocks.putChar((char) (i * Long.SIZE + bit));
				}
			}
		}

		private void writeDense() {
			ensureBlockCapacity(BLOCK_WORDS * Long.BYTES);
			for (long l : bitmap) {
				blocks.putLong(l);
			}
		}

		private int cardinality() {
			int cardinality = 0;
			for (long word : bitmap) {
				cardinality += Long.bitCount(word);
			}
			return cardinality;
		}

		public CompressedBitmap build() {
			// encode the final buffered block and flush the in-progress type mask word
			if (previousBlock >= 0) {
				appendBlock(previousBlock);
				writeTypes();
			}
			ByteBuffer data = ByteBuffer.allocate(metadata.position() + blocks.position())
					.order(ByteOrder.LITTLE_ENDIAN);
			metadata.flip();
			data.put(metadata);
			blocks.flip();
			data.put(blocks);
			data.putInt(0, COOKIE);
			data.putInt(Integer.BYTES, metadata.limit());
			return new CompressedBitmap(data);
		}

		private void ensureMetadataCapacity(int required) {
			if (metadata.remaining() < required) {
				ByteBuffer grown = ByteBuffer
						.allocate(Math.max(metadata.capacity() * 2, metadata.position() + required))
						.order(ByteOrder.LITTLE_ENDIAN);
				metadata.flip();
				grown.put(metadata);
				metadata = grown;
			}
		}

		private void ensureBlockCapacity(int required) {
			if (blocks.remaining() < required) {
				ByteBuffer grown = ByteBuffer.allocate(Math.max(blocks.capacity() * 2, blocks.position() + required))
						.order(ByteOrder.LITTLE_ENDIAN);
				blocks.flip();
				grown.put(blocks);
				blocks = grown;
			}
		}
	}

	public static Appender appender() {
		return new Appender();
	}

	private final ByteBuffer data;

	CompressedBitmap(ByteBuffer data) {
		this.data = data;
	}

	public PrimitiveIterator.OfInt iterator() {
		return new BitIterator();
	}

	public BlockIterator blockIterator() {
		return new ReadAllBlocks();
	}

	public BlockIterator and(CompressedBitmap bitmap) {
		return new And(bitmap);
	}

	public BlockIterator or(CompressedBitmap bitmap) {
		return new Or(bitmap);
	}

	public interface BlockIterator {

		default Bits newBits() {
			return new Bits();
		}

		/**
		 * Decodes the next block into {@code bits}. The {@code empty} flag is not
		 * maintained (these operations never yield empty blocks), but {@code bits} is
		 * marked {@link Bits#isFull() full} when the decoded block is full.
		 *
		 * @param bits
		 *            the contents of the block as a bitset, must be sized to store all
		 *            bits
		 * @return the block id (i.e. the high bits of the values stored shifted right)
		 */
		int nextBlock(Bits bits);

		/**
		 * Whether any blocks remain
		 */
		boolean hasNext();
	}

	private Cursor newCursor() {
		return new Cursor(this.data);
	}

	private class BitIterator implements PrimitiveIterator.OfInt {

		private final int[] rids = new int[256];

		// read cursor into the materialised batch
		private int it;
		private int count;

		// the two independent cursors: one walks the 64-block type masks, the other
		// walks the block payloads those masks describe
		private int metadataPosition;
		private int dataPosition;
		private final int dataEnd;

		// type mask covering the current group of 64 blocks
		private long typesHigh;
		private long typesLow;
		private int blockIndex;

		// decode state for the block currently being drained, preserved across batches
		// so a block larger than the batch can be resumed mid-way
		private boolean hasBlock;
		private int blockType;
		private int blockBase;
		// SPARSE / SPARSE_INVERTED: stored positions still to be read
		private int sparseRemaining;
		// SPARSE_INVERTED: next set row to emit, and the exclusive end of the current
		// run of set rows (an unset position, or BLOCK_SIZE once the unset list is
		// spent)
		private int invRow;
		private int invEnd;
		// DENSE: index of the next word to read, the partially drained current word,
		// and
		// the row id of its bit 0
		private int denseWord;
		private long denseCurrent;
		private int denseBase;

		private BitIterator() {
			this.metadataPosition = HEADER_SIZE;
			this.dataPosition = data.getInt(DATA_OFFSET);
			this.dataEnd = data.limit();
		}

		@Override
		public int nextInt() {
			return rids[it++];
		}

		@Override
		public boolean hasNext() {
			return it < count || fillBatch();
		}

		/**
		 * Materialises the next run of set bits into {@code rids}, decoding directly
		 * from the stored representation and loading further blocks as needed, until
		 * the batch is full or the payloads are exhausted.
		 *
		 * @return {@code true} if at least one row id was produced
		 */
		private boolean fillBatch() {
			it = 0;
			count = 0;
			while (count < rids.length) {
				if (!hasBlock && !loadNextBlock()) {
					break;
				}
				boolean done = switch (blockType) {
					case SPARSE -> drainSparse();
					case SPARSE_INVERTED -> drainSparseInverted();
					case DENSE -> drainDense();
					default -> throw new IllegalStateException("unexpected block type " + blockType);
				};
				if (done) {
					hasBlock = false;
				}
			}
			return count > 0;
		}

		/**
		 * Advances past any ABSENT blocks and primes the decode state for the next
		 * stored block, loading a fresh type mask word at each 64-block boundary.
		 *
		 * @return {@code true} if a block was found, {@code false} if the payloads are
		 *         exhausted
		 */
		private boolean loadNextBlock() {
			while (dataPosition < dataEnd) {
				if ((blockIndex & 63) == 0) {
					typesHigh = data.getLong(metadataPosition);
					typesLow = data.getLong(metadataPosition + Long.BYTES);
					metadataPosition += 2 * Long.BYTES;
				}
				int pos = blockIndex & 63;
				blockType = (int) ((((typesHigh >>> pos) & 1L) << 1) | ((typesLow >>> pos) & 1L));
				blockBase = blockIndex << SHIFT;
				blockIndex++;
				switch (blockType) {
					case SPARSE -> {
						sparseRemaining = data.getChar(dataPosition);
						dataPosition += Character.BYTES;
					}
					case SPARSE_INVERTED -> {
						sparseRemaining = data.getChar(dataPosition);
						dataPosition += Character.BYTES;
						invRow = 0;
						invEnd = nextInvertedBoundary();
					}
					case DENSE -> {
						denseWord = 0;
						denseCurrent = 0L;
					}
					default -> {
						continue; // ABSENT - no payload, no rows
					}
				}
				hasBlock = true;
				return true;
			}
			return false;
		}

		private boolean drainSparse() {
			while (sparseRemaining > 0 && count < rids.length) {
				rids[count++] = blockBase + data.getChar(dataPosition);
				dataPosition += Character.BYTES;
				sparseRemaining--;
			}
			return sparseRemaining == 0;
		}

		private boolean drainSparseInverted() {
			while (true) {
				while (invRow < invEnd) {
					if (count == rids.length) {
						return false;
					}
					rids[count++] = blockBase + invRow;
					invRow++;
				}
				if (invEnd == BLOCK_SIZE) {
					return true; // unset list spent and trailing run emitted
				}
				// invRow sits on an unset position: skip it and open the next run
				invRow = invEnd + 1;
				invEnd = nextInvertedBoundary();
			}
		}

		private int nextInvertedBoundary() {
			if (sparseRemaining == 0) {
				return BLOCK_SIZE;
			}
			int unset = data.getChar(dataPosition);
			dataPosition += Character.BYTES;
			sparseRemaining--;
			return unset;
		}

		private boolean drainDense() {
			while (true) {
				if (denseCurrent == 0) {
					if (denseWord == BLOCK_WORDS) {
						return true;
					}
					denseCurrent = data.getLong(dataPosition);
					dataPosition += Long.BYTES;
					denseBase = blockBase + (denseWord << 6);
					denseWord++;
					continue;
				}
				if (count == rids.length) {
					return false;
				}
				int bit = Long.numberOfTrailingZeros(denseCurrent);
				denseCurrent &= (denseCurrent - 1);
				rids[count++] = denseBase + bit;
			}
		}
	}

	private class And implements BlockIterator {

		private final Cursor a = newCursor();
		private final Cursor b;
		private boolean primed;
		// a non-empty common block has been located and both cursors left parked on it,
		// ready for nextBlock to materialise; stagedId is its block id
		private boolean staged;
		private int stagedId;

		private And(CompressedBitmap other) {
			this.b = other.newCursor();
		}

		@Override
		public boolean hasNext() {
			if (!staged) {
				staged = stageNext();
			}
			return staged;
		}

		@Override
		public int nextBlock(Bits bits) {
			if (!staged && !stageNext()) {
				throw new NoSuchElementException();
			}
			bits.setFull(intersect(bits.bits));
			bits.setEmpty(false);
			a.next();
			b.next();
			staged = false;
			return stagedId;
		}

		/**
		 * Merges the two block streams, skipping blocks absent from either side and any
		 * common block whose intersection {@link #probe() probes} empty, and parks both
		 * cursors on the first common block with a non-empty intersection.
		 *
		 * @return {@code true} if such a block was found
		 */
		private boolean stageNext() {
			if (!primed) {
				a.next();
				b.next();
				primed = true;
			}
			while (a.valid && b.valid) {
				if (a.blockId < b.blockId) {
					a.next();
				} else if (a.blockId > b.blockId) {
					b.next();
				} else if (probe()) {
					stagedId = a.blockId;
					return true;
				} else {
					a.next();
					b.next();
				}
			}
			return false;
		}

		// -- probing: does the intersection of the parked blocks contain any bit? --
		// Reads directly from the stored form and returns at the first common bit
		// found.

		/**
		 * @return whether the two parked blocks intersect at all, without materialising
		 *         the result. Operands are ordered by ascending type so each pair is
		 *         handled once.
		 */
		private boolean probe() {
			ByteBuffer da = a.data;
			ByteBuffer db = b.data;
			int pa = a.payload;
			int pb = b.payload;
			int ta = a.type;
			int tb = b.type;
			if (ta > tb) {
				int t = ta;
				ta = tb;
				tb = t;
				ByteBuffer d = da;
				da = db;
				db = d;
				int p = pa;
				pa = pb;
				pb = p;
			}
			return switch (ta) {
				case SPARSE -> switch (tb) {
					case SPARSE -> sparseIntersectsSparse(da, pa, db, pb);
					case SPARSE_INVERTED -> sparseIntersectsSparseInverted(da, pa, db, pb);
					default -> sparseIntersectsDense(da, pa, db, pb);
				};
				case SPARSE_INVERTED -> switch (tb) {
					// the two unset lists hold at most SPARSE_THRESHOLD entries each, so
					// their union can never cover the block: always non-empty
					case SPARSE_INVERTED -> true;
					default -> invertedIntersectsDense(da, pa, db, pb);
				};
				default -> denseIntersectsDense(da, pa, db, pb);
			};
		}

		private boolean sparseIntersectsSparse(ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			int ca = ba.getChar(pa);
			pa += Character.BYTES;
			int cb = bb.getChar(pb);
			pb += Character.BYTES;
			int i = 0, j = 0;
			while (i < ca && j < cb) {
				int va = ba.getChar(pa + (i << 1));
				int vb = bb.getChar(pb + (j << 1));
				if (va < vb) {
					i++;
				} else if (va > vb) {
					j++;
				} else {
					return true;
				}
			}
			return false;
		}

		private boolean sparseIntersectsSparseInverted(ByteBuffer sparse, int ps, ByteBuffer inv, int pi) {
			int cs = sparse.getChar(ps);
			ps += Character.BYTES;
			int ci = inv.getChar(pi);
			pi += Character.BYTES;
			int j = 0;
			for (int i = 0; i < cs; i++) {
				int v = sparse.getChar(ps + (i << 1));
				while (j < ci && inv.getChar(pi + (j << 1)) < v) {
					j++;
				}
				if (j == ci || inv.getChar(pi + (j << 1)) != v) {
					return true; // v is set in the inverted block
				}
			}
			return false;
		}

		private boolean sparseIntersectsDense(ByteBuffer sparse, int ps, ByteBuffer dense, int pd) {
			int cs = sparse.getChar(ps);
			ps += Character.BYTES;
			for (int i = 0; i < cs; i++) {
				int v = sparse.getChar(ps + (i << 1));
				if ((dense.getLong(pd + (v >>> 6) * Long.BYTES) & (1L << v)) != 0L) {
					return true;
				}
			}
			return false;
		}

		private boolean invertedIntersectsDense(ByteBuffer inv, int pi, ByteBuffer dense, int pd) {
			int ci = inv.getChar(pi);
			pi += Character.BYTES;
			int j = 0;
			for (int w = 0; w < BLOCK_WORDS; w++) {
				long word = dense.getLong(pd + w * Long.BYTES);
				while (j < ci) {
					int v = inv.getChar(pi + (j << 1));
					if ((v >>> 6) != w) {
						break;
					}
					word &= ~(1L << v);
					j++;
				}
				if (word != 0L) {
					return true;
				}
			}
			return false;
		}

		private boolean denseIntersectsDense(ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			for (int i = 0; i < BLOCK_WORDS; i++) {
				if ((ba.getLong(pa + i * Long.BYTES) & bb.getLong(pb + i * Long.BYTES)) != 0L) {
					return true;
				}
			}
			return false;
		}

		// -- materialising: write the intersection of the parked blocks into bits --
		// Six methods, one per (ordered) type pair, reading directly from the stored
		// form; each returns whether the result is full (only two full inverted blocks
		// can intersect to a full block).

		private boolean intersect(long[] bits) {
			ByteBuffer da = a.data;
			ByteBuffer db = b.data;
			int pa = a.payload;
			int pb = b.payload;
			int ta = a.type;
			int tb = b.type;
			if (ta > tb) {
				int t = ta;
				ta = tb;
				tb = t;
				ByteBuffer d = da;
				da = db;
				db = d;
				int p = pa;
				pa = pb;
				pb = p;
			}
			return switch (ta) {
				case SPARSE -> switch (tb) {
					case SPARSE -> sparseAndSparse(bits, da, pa, db, pb);
					case SPARSE_INVERTED -> sparseAndSparseInverted(bits, da, pa, db, pb);
					default -> sparseAndDense(bits, da, pa, db, pb);
				};
				case SPARSE_INVERTED -> switch (tb) {
					case SPARSE_INVERTED -> sparseInvertedAndSparseInverted(bits, da, pa, db, pb);
					default -> sparseInvertedAndDense(bits, da, pa, db, pb);
				};
				default -> denseAndDense(bits, da, pa, db, pb);
			};
		}

		private boolean sparseAndSparse(long[] bits, ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			Arrays.fill(bits, 0L);
			int ca = ba.getChar(pa);
			pa += Character.BYTES;
			int cb = bb.getChar(pb);
			pb += Character.BYTES;
			int i = 0, j = 0;
			while (i < ca && j < cb) {
				int va = ba.getChar(pa + (i << 1));
				int vb = bb.getChar(pb + (j << 1));
				if (va < vb) {
					i++;
				} else if (va > vb) {
					j++;
				} else {
					bits[va >>> 6] |= 1L << va;
					i++;
					j++;
				}
			}
			return false; // at most SPARSE_THRESHOLD bits, never full
		}

		private boolean sparseAndSparseInverted(long[] bits, ByteBuffer sparse, int ps, ByteBuffer inv, int pi) {
			// keep the sparse set positions that are not in the inverted block's unset list
			Arrays.fill(bits, 0L);
			int cs = sparse.getChar(ps);
			ps += Character.BYTES;
			int ci = inv.getChar(pi);
			pi += Character.BYTES;
			int j = 0;
			for (int i = 0; i < cs; i++) {
				int v = sparse.getChar(ps + (i << 1));
				while (j < ci && inv.getChar(pi + (j << 1)) < v) {
					j++;
				}
				if (j == ci || inv.getChar(pi + (j << 1)) != v) {
					bits[v >>> 6] |= 1L << v;
				}
			}
			return false; // result is a subset of the sparse block, never full
		}

		private boolean sparseAndDense(long[] bits, ByteBuffer sparse, int ps, ByteBuffer dense, int pd) {
			// keep the sparse set positions whose bit is also set in the dense block
			Arrays.fill(bits, 0L);
			int cs = sparse.getChar(ps);
			ps += Character.BYTES;
			for (int i = 0; i < cs; i++) {
				int v = sparse.getChar(ps + (i << 1));
				if ((dense.getLong(pd + (v >>> 6) * Long.BYTES) & (1L << v)) != 0L) {
					bits[v >>> 6] |= 1L << v;
				}
			}
			return false; // result is a subset of the sparse block, never full
		}

		private boolean sparseInvertedAndSparseInverted(long[] bits, ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			// everything except the union of the two unset lists; with both counts zero
			// this leaves the block full, the only way an intersection can be full
			Arrays.fill(bits, -1L);
			int ca = ba.getChar(pa);
			pa += Character.BYTES;
			int cb = bb.getChar(pb);
			pb += Character.BYTES;
			for (int i = 0; i < ca; i++) {
				int v = ba.getChar(pa + (i << 1));
				bits[v >>> 6] &= ~(1L << v);
			}
			for (int i = 0; i < cb; i++) {
				int v = bb.getChar(pb + (i << 1));
				bits[v >>> 6] &= ~(1L << v);
			}
			return ca == 0 && cb == 0;
		}

		private boolean sparseInvertedAndDense(long[] bits, ByteBuffer inv, int pi, ByteBuffer dense, int pd) {
			// start from the dense block and remove the inverted block's unset positions
			for (int i = 0; i < BLOCK_WORDS; i++) {
				bits[i] = dense.getLong(pd + i * Long.BYTES);
			}
			int ci = inv.getChar(pi);
			pi += Character.BYTES;
			for (int i = 0; i < ci; i++) {
				int v = inv.getChar(pi + (i << 1));
				bits[v >>> 6] &= ~(1L << v);
			}
			return false; // result is a subset of the dense block, never full
		}

		private boolean denseAndDense(long[] bits, ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			for (int i = 0; i < BLOCK_WORDS; i++) {
				bits[i] = ba.getLong(pa + i * Long.BYTES) & bb.getLong(pb + i * Long.BYTES);
			}
			return false; // result is a subset of each dense block, never full
		}
	}

	private class Or implements BlockIterator {

		private final Cursor a = newCursor();
		private final Cursor b;
		private boolean primed;

		private Or(CompressedBitmap other) {
			this.b = other.newCursor();
		}

		@Override
		public boolean hasNext() {
			prime();
			// a block belongs to the union whenever it is present in either bitmap, and
			// the union of a present block always has bits, so nothing is ever skipped
			return a.valid || b.valid;
		}

		@Override
		public int nextBlock(Bits bits) {
			prime();
			if (!a.valid && !b.valid) {
				throw new NoSuchElementException();
			}
			long[] words = bits.bits;
			int blockId;
			boolean full;
			if (a.valid && (!b.valid || a.blockId < b.blockId)) {
				blockId = a.blockId;
				full = decode(words, a);
				a.next();
			} else if (b.valid && (!a.valid || b.blockId < a.blockId)) {
				blockId = b.blockId;
				full = decode(words, b);
				b.next();
			} else {
				// present in both bitmaps at the same block id: take the union
				blockId = a.blockId;
				full = union(words);
				a.next();
				b.next();
			}
			bits.setFull(full);
			bits.setEmpty(false);
			return blockId;
		}

		private void prime() {
			if (!primed) {
				a.next();
				b.next();
				primed = true;
			}
		}

		// -- a block present in only one bitmap: decode it directly into bits --

		private boolean decode(long[] bits, Cursor c) {
			return switch (c.type) {
				case SPARSE -> {
					decodeSparse(bits, c.data, c.payload);
					yield false;
				}
				case SPARSE_INVERTED -> decodeSparseInverted(bits, c.data, c.payload);
				default -> {
					decodeDense(bits, c.data, c.payload);
					yield false;
				}
			};
		}

		private void decodeSparse(long[] bits, ByteBuffer buf, int pos) {
			Arrays.fill(bits, 0L);
			int count = buf.getChar(pos);
			pos += Character.BYTES;
			for (int i = 0; i < count; i++) {
				int v = buf.getChar(pos + (i << 1));
				bits[v >>> 6] |= 1L << v;
			}
		}

		private boolean decodeSparseInverted(long[] bits, ByteBuffer buf, int pos) {
			Arrays.fill(bits, -1L);
			int count = buf.getChar(pos);
			pos += Character.BYTES;
			for (int i = 0; i < count; i++) {
				int v = buf.getChar(pos + (i << 1));
				bits[v >>> 6] &= ~(1L << v);
			}
			return count == 0; // no unset positions means the block is full
		}

		private void decodeDense(long[] bits, ByteBuffer buf, int pos) {
			for (int i = 0; i < BLOCK_WORDS; i++) {
				bits[i] = buf.getLong(pos + i * Long.BYTES);
			}
		}

		// -- a block present in both bitmaps: write their union into bits --
		// Six methods, one per (ordered) type pair, reading directly from the stored
		// form; each returns whether the union is full (every combination except two
		// sparse blocks can produce a full result).

		private boolean union(long[] bits) {
			ByteBuffer da = a.data;
			ByteBuffer db = b.data;
			int pa = a.payload;
			int pb = b.payload;
			int ta = a.type;
			int tb = b.type;
			if (ta > tb) {
				int t = ta;
				ta = tb;
				tb = t;
				ByteBuffer d = da;
				da = db;
				db = d;
				int p = pa;
				pa = pb;
				pb = p;
			}
			return switch (ta) {
				case SPARSE -> switch (tb) {
					case SPARSE -> sparseOrSparse(bits, da, pa, db, pb);
					case SPARSE_INVERTED -> sparseOrSparseInverted(bits, da, pa, db, pb);
					default -> sparseOrDense(bits, da, pa, db, pb);
				};
				case SPARSE_INVERTED -> switch (tb) {
					case SPARSE_INVERTED -> sparseInvertedOrSparseInverted(bits, da, pa, db, pb);
					default -> sparseInvertedOrDense(bits, da, pa, db, pb);
				};
				default -> denseOrDense(bits, da, pa, db, pb);
			};
		}

		private boolean sparseOrSparse(long[] bits, ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			// at most SPARSE_THRESHOLD set bits each, so the union can never be full
			Arrays.fill(bits, 0L);
			int ca = ba.getChar(pa);
			pa += Character.BYTES;
			int cb = bb.getChar(pb);
			pb += Character.BYTES;
			for (int i = 0; i < ca; i++) {
				int v = ba.getChar(pa + (i << 1));
				bits[v >>> 6] |= 1L << v;
			}
			for (int i = 0; i < cb; i++) {
				int v = bb.getChar(pb + (i << 1));
				bits[v >>> 6] |= 1L << v;
			}
			return false;
		}

		private boolean sparseOrSparseInverted(long[] bits, ByteBuffer sparse, int ps, ByteBuffer inv, int pi) {
			// the inverted block is full minus its unset list; a position is unset in the
			// union only if it is unset in the inverted block and absent from the sparse
			// block, so clear those positions and leave everything else set
			Arrays.fill(bits, -1L);
			int ci = inv.getChar(pi);
			pi += Character.BYTES;
			int cs = sparse.getChar(ps);
			ps += Character.BYTES;
			boolean full = true;
			int j = 0;
			for (int i = 0; i < ci; i++) {
				int v = inv.getChar(pi + (i << 1));
				while (j < cs && sparse.getChar(ps + (j << 1)) < v) {
					j++;
				}
				if (j == cs || sparse.getChar(ps + (j << 1)) != v) {
					bits[v >>> 6] &= ~(1L << v);
					full = false;
				}
			}
			return full;
		}

		private boolean sparseOrDense(long[] bits, ByteBuffer sparse, int ps, ByteBuffer dense, int pd) {
			// start from the dense block and add the sparse positions; a dense block
			// misses more positions than a sparse block can supply, so it is never full
			for (int i = 0; i < BLOCK_WORDS; i++) {
				bits[i] = dense.getLong(pd + i * Long.BYTES);
			}
			int cs = sparse.getChar(ps);
			ps += Character.BYTES;
			for (int i = 0; i < cs; i++) {
				int v = sparse.getChar(ps + (i << 1));
				bits[v >>> 6] |= 1L << v;
			}
			return false;
		}

		private boolean sparseInvertedOrSparseInverted(long[] bits, ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			// a position is unset in the union only if it is unset in both blocks, so
			// clear exactly the intersection of the two unset lists
			Arrays.fill(bits, -1L);
			int ca = ba.getChar(pa);
			pa += Character.BYTES;
			int cb = bb.getChar(pb);
			pb += Character.BYTES;
			boolean full = true;
			int i = 0, j = 0;
			while (i < ca && j < cb) {
				int va = ba.getChar(pa + (i << 1));
				int vb = bb.getChar(pb + (j << 1));
				if (va < vb) {
					i++;
				} else if (va > vb) {
					j++;
				} else {
					bits[va >>> 6] &= ~(1L << va);
					full = false;
					i++;
					j++;
				}
			}
			return full;
		}

		private boolean sparseInvertedOrDense(long[] bits, ByteBuffer inv, int pi, ByteBuffer dense, int pd) {
			// start full; an unset position of the inverted block survives only where the
			// dense block also lacks it
			Arrays.fill(bits, -1L);
			int ci = inv.getChar(pi);
			pi += Character.BYTES;
			boolean full = true;
			for (int i = 0; i < ci; i++) {
				int v = inv.getChar(pi + (i << 1));
				if ((dense.getLong(pd + (v >>> 6) * Long.BYTES) & (1L << v)) == 0L) {
					bits[v >>> 6] &= ~(1L << v);
					full = false;
				}
			}
			return full;
		}

		private boolean denseOrDense(long[] bits, ByteBuffer ba, int pa, ByteBuffer bb, int pb) {
			long all = -1L;
			for (int i = 0; i < BLOCK_WORDS; i++) {
				long word = ba.getLong(pa + i * Long.BYTES) | bb.getLong(pb + i * Long.BYTES);
				bits[i] = word;
				all &= word;
			}
			return all == -1L;
		}
	}

	private class ReadAllBlocks implements BlockIterator {

		// one cursor walks the 64-block type masks, the other the block payloads
		private int metadataPosition = HEADER_SIZE;
		private int dataPosition = data.getInt(DATA_OFFSET);
		private final int dataEnd = data.limit();

		// type mask covering the current group of 64 blocks
		private long typesHigh;
		private long typesLow;
		private int blockIndex;

		@Override
		public boolean hasNext() {
			// every stored block consumes payload, so leftover payload means another
			// present block remains; ABSENT gaps carry no payload
			return dataPosition < dataEnd;
		}

		@Override
		public int nextBlock(Bits bits) {
			long[] words = bits.bits;
			while (dataPosition < dataEnd) {
				if ((blockIndex & 63) == 0) {
					typesHigh = data.getLong(metadataPosition);
					typesLow = data.getLong(metadataPosition + Long.BYTES);
					metadataPosition += 2 * Long.BYTES;
				}
				int pos = blockIndex & 63;
				int type = (int) ((((typesHigh >>> pos) & 1L) << 1) | ((typesLow >>> pos) & 1L));
				int blockId = blockIndex++;
				bits.setEmpty(false);
				switch (type) {
					case SPARSE -> {
						decodeSparse(words);
						bits.setFull(false);
						return blockId;
					}
					case SPARSE_INVERTED -> {
						bits.setFull(decodeSparseInverted(words));
						return blockId;
					}
					case DENSE -> {
						decodeDense(words);
						bits.setFull(false);
						return blockId;
					}
					default -> {
						// ABSENT - no payload, no set bits; skip to the next block
					}
				}
			}
			throw new NoSuchElementException();
		}

		private void decodeSparse(long[] bits) {
			Arrays.fill(bits, 0L);
			int count = data.getChar(dataPosition);
			dataPosition += Character.BYTES;
			for (int i = 0; i < count; i++) {
				int p = data.getChar(dataPosition);
				dataPosition += Character.BYTES;
				bits[p >>> 6] |= 1L << p;
			}
		}

		private boolean decodeSparseInverted(long[] bits) {
			// stored positions are the unset bits; everything else is set
			Arrays.fill(bits, -1L);
			int count = data.getChar(dataPosition);
			dataPosition += Character.BYTES;
			for (int i = 0; i < count; i++) {
				int p = data.getChar(dataPosition);
				dataPosition += Character.BYTES;
				bits[p >>> 6] &= ~(1L << p);
			}
			return count == 0; // no unset positions means the block is full
		}

		private void decodeDense(long[] bits) {
			for (int i = 0; i < BLOCK_WORDS; i++) {
				bits[i] = data.getLong(dataPosition);
				dataPosition += Long.BYTES;
			}
		}
	}

	/**
	 * Walks the present (non-ABSENT) blocks of a single bitmap in ascending block
	 * id order, exposing the storage type and payload offset of the current block
	 * without decoding it.
	 */
	private static final class Cursor {
		private final ByteBuffer data;
		private int metadataPosition = HEADER_SIZE;
		private int dataPosition;
		private final int dataEnd;
		private long typesHigh;
		private long typesLow;
		private int blockIndex;

		// the block the cursor is currently positioned on
		private int blockId;
		private int type;
		private int payload;
		private boolean valid;

		private Cursor(ByteBuffer data) {
			this.data = data;
			this.dataPosition = data.getInt(DATA_OFFSET);
			this.dataEnd = data.limit();
		}

		/**
		 * Advances to the next present block.
		 *
		 * @return {@code true} if positioned on a block, {@code false} once exhausted
		 */
		private boolean next() {
			while (dataPosition < dataEnd) {
				if ((blockIndex & 63) == 0) {
					typesHigh = data.getLong(metadataPosition);
					typesLow = data.getLong(metadataPosition + Long.BYTES);
					metadataPosition += 2 * Long.BYTES;
				}
				int pos = blockIndex & 63;
				int t = (int) ((((typesHigh >>> pos) & 1L) << 1) | ((typesLow >>> pos) & 1L));
				int id = blockIndex++;
				if (t == ABSENT) {
					continue;
				}
				this.blockId = id;
				this.type = t;
				this.payload = dataPosition;
				this.dataPosition = skipPayload(t, dataPosition);
				this.valid = true;
				return true;
			}
			valid = false;
			return false;
		}

		private int skipPayload(int type, int position) {
			return switch (type) {
				case SPARSE, SPARSE_INVERTED -> position + Character.BYTES * (1 + data.getChar(position));
				case DENSE -> position + BLOCK_WORDS * Long.BYTES;
				default -> position;
			};
		}
	}
}

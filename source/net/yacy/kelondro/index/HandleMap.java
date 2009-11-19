// HandleMap.java
// (C) 2008 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 08.04.2008 on http://yacy.net
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.kelondro.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.ByteOrder;
import net.yacy.kelondro.order.CloneableIterator;


public final class HandleMap implements Iterable<Row.Entry> {
    
    private   final Row rowdef;
    protected ObjectIndexCache index;
    
    /**
     * initialize a HandleMap
     * This may store a key and a long value for each key.
     * The class is used as index for database files
     * @param keylength
     * @param objectOrder
     * @param space
     */
    public HandleMap(final int keylength, final ByteOrder objectOrder, int idxbytes, final int initialspace, final int expectedspace) {
        this.rowdef = new Row(new Column[]{new Column("key", Column.celltype_binary, Column.encoder_bytes, keylength, "key"), new Column("long c-" + idxbytes + " {b256}")}, objectOrder);
        this.index = new ObjectIndexCache(rowdef, initialspace, expectedspace);
    }

    /**
     * initialize a HandleMap with the content of a dumped index
     * @param keylength
     * @param objectOrder
     * @param file
     * @throws IOException 
     */
    public HandleMap(final int keylength, final ByteOrder objectOrder, int idxbytes, final File file, final int expectedspace) throws IOException {
        this(keylength, objectOrder, idxbytes, (int) (file.length() / (keylength + idxbytes)), expectedspace);
        // read the index dump and fill the index
        InputStream is = new BufferedInputStream(new FileInputStream(file), 1024 * 1024);
        if (file.getName().endsWith(".gz")) is = new GZIPInputStream(is);
        byte[] a = new byte[keylength + idxbytes];
        int c;
        Row.Entry entry;
        while (true) {
            c = is.read(a);
            if (c <= 0) break;
            entry = this.rowdef.newEntry(a); // may be null if a is not well-formed
            if (entry != null) this.index.addUnique(entry);
        }
        is.close();
        is = null;
        assert this.index.size() == file.length() / (keylength + idxbytes);
    }
    
    public final int[] saturation() {
    	int keym = 0;
    	int valm = this.rowdef.width(1);
    	int valc;
    	byte[] lastk = null, thisk;
    	for (Row.Entry row: this) {
    		// check length of key
    		if (lastk == null) {
    			lastk = row.bytes();
    		} else {
    			thisk = row.bytes();
    			keym = Math.max(keym, eq(lastk, thisk));
    			lastk = thisk;
    		}

    		// check length of value
    		for (valc = this.rowdef.primaryKeyLength; valc < this.rowdef.objectsize; valc++) {
    			if (lastk[valc] != 0) break;
    		} // valc is the number of leading zeros plus primaryKeyLength
    		valm = Math.min(valm, valc - this.rowdef.primaryKeyLength); // valm is the number of leading zeros
    	}
    	return new int[]{keym, this.rowdef.width(1) - valm};
    }

    private final int eq(byte[] a, byte[] b) {
    	for (int i = 0; i < a.length; i++) {
    		if (a[i] != b[i]) return i;
    	}
    	return a.length;
    }
    
    /**
     * write a dump of the index to a file. All entries are written in order
     * which makes it possible to read them again in a fast way
     * @param file
     * @return the number of written entries
     * @throws IOException
     */
    public final int dump(File file) throws IOException {
        // we must use an iterator from the combined index, because we need the entries sorted
        // otherwise we could just write the byte[] from the in kelondroRowSet which would make
        // everything much faster, but this is not an option here.
        File tmp = new File(file.getParentFile(), file.getName() + ".prt");
        Iterator<Row.Entry> i = this.index.rows(true, null);
        OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 4 * 1024 * 1024);
        if (file.getName().endsWith(".gz")) os = new GZIPOutputStream(os);
        int c = 0;
        while (i.hasNext()) {
            os.write(i.next().bytes());
            c++;
        }
        os.flush();
        os.close();
        tmp.renameTo(file);
        assert file.exists() : file.toString();
        assert !tmp.exists() : tmp.toString();
        return c;
    }

    public final Row row() {
        return index.row();
    }
    
    public final void clear() {
        index.clear();
    }
    
    public final synchronized byte[] smallestKey() {
        return index.smallestKey();
    }
    
    public final synchronized byte[] largestKey() {
        return index.largestKey();
    }
    
    public final synchronized boolean has(final byte[] key) {
        assert (key != null);
        return index.has(key);
    }
    
    public final synchronized long get(final byte[] key) {
        assert (key != null);
        final Row.Entry indexentry = index.get(key);
        if (indexentry == null) return -1;
        return indexentry.getColLong(1);
    }
    
    public final synchronized long put(final byte[] key, final long l) {
        assert l >= 0 : "l = " + l;
        assert (key != null);
        final Row.Entry newentry = index.row().newEntry();
        newentry.setCol(0, key);
        newentry.setCol(1, l);
        final Row.Entry oldentry = index.replace(newentry);
        if (oldentry == null) return -1;
        return oldentry.getColLong(1);
    }
    
    public final synchronized void putUnique(final byte[] key, final long l) {
        assert l >= 0 : "l = " + l;
        assert (key != null);
        final Row.Entry newentry = this.rowdef.newEntry();
        newentry.setCol(0, key);
        newentry.setCol(1, l);
        index.addUnique(newentry);
    }
    
    public final synchronized long add(final byte[] key, long a) {
        assert key != null;
        assert a > 0; // it does not make sense to add 0. If this occurres, it is a performance issue

        final Row.Entry indexentry = index.get(key);
        if (indexentry == null) {
            final Row.Entry newentry = this.rowdef.newEntry();
            newentry.setCol(0, key);
            newentry.setCol(1, a);
            index.addUnique(newentry);
            return 1;
        }
        long i = indexentry.getColLong(1) + a;
        indexentry.setCol(1, i);
        index.put(indexentry);
        return i;
    }
    
    public final synchronized long inc(final byte[] key) {
        return add(key, 1);
    }
    
    public final synchronized long dec(final byte[] key) {
        return add(key, -1);
    }
    
    public final synchronized ArrayList<Long[]> removeDoubles() {
        final ArrayList<Long[]> report = new ArrayList<Long[]>();
        Long[] is;
        int c;
        long l;
        final int initialSize = this.size();
        for (final RowCollection rowset: index.removeDoubles()) {
            is = new Long[rowset.size()];
            c = 0;
            for (Row.Entry e: rowset) {
            	l = e.getColLong(1);
            	assert l < initialSize : "l = " + l + ", initialSize = " + initialSize;
                is[c++] = Long.valueOf(l);
            }
            report.add(is);
        }
        return report;
    }
    
    public final synchronized long remove(final byte[] key) {
        assert (key != null);
        final Row.Entry indexentry = index.remove(key);
        if (indexentry == null) return -1;
        return indexentry.getColLong(1);
    }

    public final synchronized long removeone() {
        final Row.Entry indexentry = index.removeOne();
        if (indexentry == null) return -1;
        return indexentry.getColLong(1);
    }
    
    public final synchronized int size() {
        return index.size();
    }
    
    public final synchronized CloneableIterator<byte[]> keys(final boolean up, final byte[] firstKey) {
        return index.keys(up, firstKey);
    }

    public final synchronized CloneableIterator<Row.Entry> rows(final boolean up, final byte[] firstKey) {
        return index.rows(up, firstKey);
    }
    
    public final synchronized void close() {
        index.close();
        index = null;
    }
    
    /**
     * this method creates a concurrent thread that can take entries that are used to initialize the map
     * it should be used when a HandleMap is initialized when a file is read. Concurrency of FileIO and
     * map creation will speed up the initialization process.
     * @param keylength
     * @param objectOrder
     * @param space
     * @param bufferSize
     * @return
     */
    public final static initDataConsumer asynchronusInitializer(final int keylength, final ByteOrder objectOrder, int idxbytes, final int space, final int expectedspace) {
        initDataConsumer initializer = new initDataConsumer(new HandleMap(keylength, objectOrder, idxbytes, space, expectedspace));
        ExecutorService service = Executors.newSingleThreadExecutor();
        initializer.setResult(service.submit(initializer));
        service.shutdown();
        return initializer;
    }

    private final static class entry {
        public byte[] key;
        public long l;
        public entry(final byte[] key, final long l) {
            this.key = key;
            this.l = l;
        }
    }
    
    protected static final entry poisonEntry = new entry(new byte[0], 0);
    
    public final static class initDataConsumer implements Callable<HandleMap> {

        private BlockingQueue<entry> cache;
        private HandleMap map;
        private Future<HandleMap> result;
        private boolean sortAtEnd;
        
        public initDataConsumer(HandleMap map) {
            this.map = map;
            cache = new LinkedBlockingQueue<entry>();
            sortAtEnd = false;
        }
        
        protected final void setResult(Future<HandleMap> result) {
            this.result = result;
        }
        
        /**
         * hand over another entry that shall be inserted into the HandleMap with an addl method
         * @param key
         * @param l
         */
        public final void consume(final byte[] key, final long l) {
            try {
                cache.put(new entry(key, l));
            } catch (InterruptedException e) {
                Log.logException(e);
            }
        }
        
        /**
         * to signal the initialization thread that no more entries will be submitted with consumer()
         * this method must be called. The process will not terminate if this is not called before.
         */
        public final void finish(boolean sortAtEnd) {
            this.sortAtEnd = sortAtEnd;
            try {
                cache.put(poisonEntry);
            } catch (InterruptedException e) {
                Log.logException(e);
            }
        }
        
        /**
         * this must be called after a finish() was called. this method blocks until all entries
         * had been processed, and the content was sorted. It returns the HandleMap
         * that the user wanted to initialize
         * @return
         * @throws InterruptedException
         * @throws ExecutionException
         */
        public final HandleMap result() throws InterruptedException, ExecutionException {
            return this.result.get();
        }
        
        public final HandleMap call() throws IOException {
            try {
                entry c;
                while ((c = cache.take()) != poisonEntry) {
                    map.putUnique(c.key, c.l);
                }
            } catch (InterruptedException e) {
                Log.logException(e);
            }
            if (sortAtEnd) {
                map.index.finishInitialization();
            }
            return map;
        }
        
    }

	public Iterator<Row.Entry> iterator() {
		return this.rows(true, null);
	}
}
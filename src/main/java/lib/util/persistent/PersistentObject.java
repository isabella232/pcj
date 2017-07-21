/* Copyright (C) 2017  Intel Corporation
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 only, as published by the Free Software Foundation.
 * This file has been designated as subject to the "Classpath"
 * exception as provided in the LICENSE file that accompanied
 * this code.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License version 2 for more details (a copy
 * is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this program; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package lib.util.persistent;

import lib.util.persistent.types.Types;
import lib.util.persistent.types.PersistentType;
import lib.util.persistent.types.ObjectType;
import lib.util.persistent.types.ValueType;
import lib.util.persistent.types.ArrayType;
import lib.util.persistent.types.CarriedType;
import lib.util.persistent.types.ByteField;
import lib.util.persistent.types.ShortField;
import lib.util.persistent.types.IntField;
import lib.util.persistent.types.LongField;
import lib.util.persistent.types.FloatField;
import lib.util.persistent.types.DoubleField;
import lib.util.persistent.types.CharField;
import lib.util.persistent.types.BooleanField;
import lib.util.persistent.types.ObjectField;
import lib.util.persistent.types.ValueField;
import lib.util.persistent.types.PersistentField;
import lib.util.persistent.spi.PersistentMemoryProvider;
import java.util.List;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.lang.reflect.Constructor;
import java.util.Iterator;
import lib.xpersistent.XHeap;
import lib.xpersistent.XRoot;
import lib.xpersistent.UncheckedPersistentMemoryRegion;
import java.util.Random;
import static lib.util.persistent.Trace.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import sun.misc.Unsafe;

@SuppressWarnings("sunapi")
public class PersistentObject implements Persistent<PersistentObject> {
    static final PersistentHeap heap = PersistentMemoryProvider.getDefaultProvider().getHeap();
    private static Random random = new Random(System.nanoTime());
    public static Unsafe UNSAFE;

    // for stats
    static {
        try {
            java.lang.reflect.Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe)f.get(null);
        }
        catch (Exception e) {
            throw new RuntimeException("Unable to initialize UNSAFE.");
        }
    }

    private final ObjectPointer<? extends PersistentObject> pointer;

    public PersistentObject(ObjectType<? extends PersistentObject> type) {
        // would like to group the allocation transaction and initialization transaction
        // can't just put one here because this constructor call must be first line
        // Transaction.run(() -> {
            this(type, PersistentMemoryProvider.getDefaultProvider().getHeap().allocateRegion(type.getAllocationSize()));
        // });
    }

    <T extends PersistentObject> PersistentObject(ObjectType<T> type, MemoryRegion region) {
        // trace(region.addr(), "creating object of type %s", type.getName());
        Stats.memory.constructions++;
        this.pointer = new ObjectPointer<T>(type, region);
        List<PersistentType> ts = type.getTypes();
        Transaction.run(() -> {
            for (int i = 0; i < ts.size(); i++) initializeField(i, ts.get(i));
            setTypeName(type.getName());
            setVersion(99);
            initForGC();
            if (heap instanceof XHeap && ((XHeap)heap).getDebugMode() == true) {
                ((XRoot)(heap.getRoot())).addToAllObjects(getPointer().region().addr());
            }
        }, this);
        ObjectCache.add(this);
    }

    public PersistentObject(ObjectPointer<? extends PersistentObject> p) {
        // trace(p.region().addr(), "recreating object");
        Stats.memory.reconstructions++;
        this.pointer = p;
    }

    void initForGC() {
        Transaction.run(() -> {
            incRefCount();
            ObjectCache.registerObject(this);
        }, this);
    }

    // only called by Root during bootstrap of Object directory PersistentHashMap
    @SuppressWarnings("unchecked")
    public static <T extends PersistentObject> T fromPointer(ObjectPointer<T> p) {
        // trace(p.addr(), "creating object from pointer of type %s", p.type().getName());
        try {
            Class<T> cls = p.type().cls();
            Constructor ctor = cls.getDeclaredConstructor(ObjectPointer.class);
            ctor.setAccessible(true);
            T obj = (T)ctor.newInstance(p);
            return obj;
        }
        catch (Exception e) {e.printStackTrace();}
        return null;
    }

    static void free(long addr) {
        // trace(addr, "free called");
        ObjectCache.remove(addr);
        MemoryRegion reg = new UncheckedPersistentMemoryRegion(addr);
        MemoryRegion nameRegion = new UncheckedPersistentMemoryRegion(reg.getLong(Header.TYPE.getOffset(Header.TYPE_NAME)));
        Transaction.run(() -> {
            // trace(addr, "freeing object region %d and name region %d", reg.addr(), nameRegion.addr());
            heap.freeRegion(nameRegion);
            heap.freeRegion(reg);
            if (heap instanceof XHeap && ((XHeap)heap).getDebugMode() == true) {
                ((XRoot)(heap.getRoot())).removeFromAllObjects(addr);
            }
            CycleCollector.removeFromCandidates(addr);
        });
    }

    public ObjectPointer<? extends PersistentObject> getPointer() {
        return pointer;
    }

    ObjectType getType() {
        return pointer.type();
    }

    byte getByte(long offset) {
        // return Util.synchronizedBlock(this, () -> pointer.region().getByte(offset));
        byte ans;
        TransactionInfo info = lib.xpersistent.XTransaction.tlInfo.get();
        boolean inTransaction = info.state == Transaction.State.Active;
        if (!inTransaction) {
            monitorEnter();
            ans = pointer.region().getByte(offset);
            monitorExit();
        }
        else {
            boolean success = monitorEnterTimeout();
            if (success) {
                info.transaction.addLockedObject(this);
                return pointer.region().getByte(offset);
            }
            else {
                if (inTransaction) throw new TransactionRetryException();
                else throw new RuntimeException("failed to acquire lock (timeout)");
            }
        }
        return ans;
    }

    short getShort(long offset) {
        // return Util.synchronizedBlock(this, () -> pointer.region().getShort(offset));
        short ans;
        TransactionInfo info = lib.xpersistent.XTransaction.tlInfo.get();
        boolean inTransaction = info.state == Transaction.State.Active;
        if (!inTransaction) {
            monitorEnter();
            ans = pointer.region().getShort(offset);
            monitorExit();
        }
        else {
            boolean success = monitorEnterTimeout();
            if (success) {
                info.transaction.addLockedObject(this);
                ans = pointer.region().getShort(offset);
            }
            else {
                if (inTransaction) throw new TransactionRetryException();
                else throw new RuntimeException("failed to acquire lock (timeout)");
            }
        }
        return ans;
    }

    int getInt(long offset) {
        // return Util.synchronizedBlock(this, () -> pointer.region().getInt(offset));
        int ans;
        TransactionInfo info = lib.xpersistent.XTransaction.tlInfo.get();
        boolean inTransaction = info.state == Transaction.State.Active;
        if (!inTransaction) {
            monitorEnter();
            ans = pointer.region().getInt(offset);
            monitorExit();
        }
        else {
            boolean success = monitorEnterTimeout();
            if (success) {
                info.transaction.addLockedObject(this);
                ans = pointer.region().getInt(offset);
            }
            else {
                if (inTransaction) throw new TransactionRetryException();
                else throw new RuntimeException("failed to acquire lock (timeout)");
            }
        }
        return ans;
    }

    long getLong(long offset) {
        // return Util.synchronizedBlock(this, () -> pointer.region().getLong(offset));
        long ans;
        TransactionInfo info = lib.xpersistent.XTransaction.tlInfo.get();
        boolean inTransaction = info.state == Transaction.State.Active;
        if (!inTransaction) {
            monitorEnter();
            ans = pointer.region().getLong(offset);
            monitorExit();
        }
        else {
            boolean success = monitorEnterTimeout();
            if (success) {
                info.transaction.addLockedObject(this);
                ans = pointer.region().getLong(offset);
            }
            else {
                if (inTransaction) throw new TransactionRetryException();
                else throw new RuntimeException("failed to acquire lock (timeout)");
            }
        }
        return ans;
    }

    void setByte(long offset, byte value) {
        Transaction.run(() -> {
            pointer.region().putByte(offset, value);
        }, this);
    }

    void setShort(long offset, short value) {
        Transaction.run(() -> {
            pointer.region().putShort(offset, value);
        }, this);
    }

    void setInt(long offset, int value) {
        Transaction.run(() -> {
            pointer.region().putInt(offset, value);
        }, this);
    }

    void setLong(long offset, long value) {
        Transaction.run(() -> {
            pointer.region().putLong(offset, value);
        }, this);
    }

    @SuppressWarnings("unchecked")
    <T extends PersistentObject> T getObject(long offset) {
        T ans = null;
        TransactionInfo info = lib.xpersistent.XTransaction.tlInfo.get();
        boolean inTransaction = info.state == Transaction.State.Active;
        boolean success = inTransaction ? monitorEnterTimeout() : monitorEnterTimeout(5000);
        if (success) {
            try {
                if (inTransaction) info.transaction.addLockedObject(this);
                long valueAddr = getLong(offset);
                if (valueAddr != 0) ans = (T)ObjectCache.get(valueAddr);
            }
            finally {
                if (!inTransaction) monitorExit();
            }
        }
        else {
            String message = "failed to acquire lock (timeout) in getObject";
            trace(true, getPointer().addr(), message + ", inTransaction = %s", inTransaction);
            if (inTransaction) throw new TransactionRetryException(message);
            else throw new RuntimeException(message);
        }
        return ans;
    }

    void setObject(long offset, PersistentObject value) {
        Transaction.run(() -> {
            PersistentObject old = ObjectCache.get(getLong(offset), true);
            Transaction.run(() -> {
                if (value != null) value.addReference();
                if (old != null) old.deleteReference();
                setLong(offset, value == null ? 0 : value.getPointer().addr());
            }, value, old);
        }, this);
    }

    byte getByteField(int index) {return getByte(offset(check(index, Types.BYTE)));}
    short getShortField(int index) {return getShort(offset(check(index, Types.SHORT)));}
    int getIntField(int index) {return getInt(offset(check(index, Types.INT)));}
    long getLongField(int index) {return getLong(offset(check(index, Types.LONG)));}
    float getFloatField(int index) {return Float.intBitsToFloat(getInt(offset(check(index, Types.FLOAT))));}
    double getDoubleField(int index) {return Double.longBitsToDouble(getLong(offset(check(index, Types.DOUBLE))));}
    char getCharField(int index) {return (char)getInt(offset(check(index, Types.CHAR)));}
    boolean getBooleanField(int index) {return getByte(offset(check(index, Types.BOOLEAN))) == 0 ? false : true;}
    PersistentObject getObjectField(int index) {return getObject(offset(check(index, Types.OBJECT)));}

    void setByteField(int index, byte value) {setByte(offset(check(index, Types.BYTE)), value);}
    void setShortField(int index, short value) {setShort(offset(check(index, Types.SHORT)), value);}
    void setIntField(int index, int value) {setInt(offset(check(index, Types.INT)), value);}
    void setLongField(int index, long value) {setLong(offset(check(index, Types.LONG)), value);}
    void setFloatField(int index, float value) {setInt(offset(check(index, Types.FLOAT)), Float.floatToIntBits(value));}
    void setDoubleField(int index, double value) {setLong(offset(check(index, Types.DOUBLE)), Double.doubleToLongBits(value));}
    void setCharField(int index, char value) {setInt(offset(check(index, Types.CHAR)), (int)value);}
    void setBooleanField(int index, boolean value) {setByte(offset(check(index, Types.BOOLEAN)), value ? (byte)1 : (byte)0);}
    void setObjectField(int index, PersistentObject value) {setObject(offset(check(index, Types.OBJECT)), value);}

    public byte getByteField(ByteField f) {return getByte(offset(check(f.getIndex(), Types.BYTE)));}
    public short getShortField(ShortField f) {return getShort(offset(check(f.getIndex(), Types.SHORT)));}
    public int getIntField(IntField f) {return getInt(offset(check(f.getIndex(), Types.INT)));}
    public long getLongField(LongField f) {return getLong(offset(check(f.getIndex(), Types.LONG)));}
    public float getFloatField(FloatField f) {return Float.intBitsToFloat(getInt(offset(check(f.getIndex(), Types.FLOAT))));}
    public double getDoubleField(DoubleField f) {return Double.longBitsToDouble(getLong(offset(check(f.getIndex(), Types.DOUBLE))));}
    public char getCharField(CharField f) {return (char)getInt(offset(check(f.getIndex(), Types.CHAR)));}
    public boolean getBooleanField(BooleanField f) {return getByte(offset(check(f.getIndex(), Types.BOOLEAN))) == 0 ? false : true;}
    @SuppressWarnings("unchecked") public <T extends PersistentObject> T getObjectField(ObjectField<T> f) {return (T)getObjectField(f.getIndex());}

    @SuppressWarnings("unchecked")
    public synchronized <T extends PersistentValue> T getValueField(ValueField<T> f) {
        MemoryRegion srcRegion = getPointer().region();
        MemoryRegion dstRegion = heap.allocateRegion(f.getType().getSize());
        // trace("getValueField src addr = %d, dst addr = %d, size = %d", srcRegion.addr(), dstRegion.addr(), f.getType().getSize());
        synchronized(srcRegion) {
            synchronized(dstRegion) {
                ((lib.xpersistent.XHeap)heap).memcpy(srcRegion, offset(f.getIndex()), dstRegion, 0, f.getType().getSize());
            }
        }
        return (T)new ValuePointer((ValueType)f.getType(), dstRegion, f.cls()).deref();
    }

    public synchronized <T extends PersistentValue> void setValueField(ValueField<T> f, T value) {
        MemoryRegion dstRegion = getPointer().region();
        long dstOffset = offset(f.getIndex());
        MemoryRegion srcRegion = value.getPointer().region();
        // trace("setValueField src addr = %d, dst addr = %d, size = %d", srcRegion.addr(), dstRegion.addr() + dstOffset, f.getType().getSize());
        synchronized(srcRegion) {
            ((lib.xpersistent.XHeap)heap).memcpy(srcRegion, 0, dstRegion, dstOffset, f.getType().getSize());
        }
    }

    public void setByteField(ByteField f, byte value) {setByte(offset(check(f.getIndex(), Types.BYTE)), value);}
    public void setShortField(ShortField f, short value) {setShort(offset(check(f.getIndex(), Types.SHORT)), value);}
    public void setIntField(IntField f, int value) {setInt(offset(check(f.getIndex(), Types.INT)), value);}
    public void setLongField(LongField f, long value) {setLong(offset(check(f.getIndex(), Types.LONG)), value);}
    public void setFloatField(FloatField f, float value) {setInt(offset(check(f.getIndex(), Types.FLOAT)), Float.floatToIntBits(value));}
    public void setDoubleField(DoubleField f, double value) {setLong(offset(check(f.getIndex(), Types.DOUBLE)), Double.doubleToLongBits(value));}
    public void setCharField(CharField f, char value) {setInt(offset(check(f.getIndex(), Types.CHAR)), (int)value);}
    public void setBooleanField(BooleanField f, boolean value) {setByte(offset(check(f.getIndex(), Types.BOOLEAN)), value ? (byte)1 : (byte)0);}
    public <T extends PersistentObject> void setObjectField(ObjectField<T> f, T value) {setObjectField(f.getIndex(), value);}

    // identity beyond one JVM instance
    public final boolean is(PersistentObject obj) {
        return getPointer().region().addr() == obj.getPointer().region().addr();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PersistentObject && ((PersistentObject)obj).is(this);
    }

    @Override
    public int hashCode() {
        return (int)getPointer().region().addr();
    }

    private List<PersistentType> types() {
        return ((ObjectType<?>)pointer.type()).getTypes();
    }

    private int fieldCount() {
        return types().size();
    }

    private long offset(int index) {
        return ((ObjectType<?>)getPointer().type()).getOffset(index);
    }

    private void initializeField(int index, PersistentType t)
    {
        if (t == Types.BYTE) setByteField(index, (byte)0);
        else if (t == Types.SHORT) setShortField(index, (short)0);
        else if (t == Types.INT) setIntField(index, 0);
        else if (t == Types.LONG) setLongField(index, 0L);
        else if (t == Types.FLOAT) setFloatField(index, 0f);
        else if (t == Types.DOUBLE) setDoubleField(index, 0d);
        else if (t == Types.CHAR) setCharField(index, (char)0);
        else if (t == Types.BOOLEAN) setBooleanField(index, false);
        else if (t instanceof ObjectType) setObjectField(index, null);
    }

    // we can turn this off after debug since only Field-based getters and setters are public
    // and those provide static type safety and internally assigned indexes
    private int check(int index, PersistentType testType) {
        boolean result = true;
        if (index < 0 || index >= fieldCount()) throw new IndexOutOfBoundsException("No such field index: " + index);
        PersistentType t = types().get(index);
        if (t instanceof ObjectType && testType instanceof ObjectType) {
            ObjectType<?> fieldType = (ObjectType)t;
            ObjectType<?> test = (ObjectType) testType;
            if (!test.cls().isAssignableFrom(fieldType.cls())) result = false;
            else if (t != testType) result = false;
            if (!result) throw new RuntimeException("Type mismatch in " + getType().cls() + " at index " + index + ": expected " + testType + ", found " + types().get(index));
        }
        return index;
    }

    private int getVersion() {
        return getIntField(Header.VERSION);
    }

    private void setVersion(int version) {
        setIntField(Header.VERSION, version);
    }

    private void setTypeName(String name) {
        Transaction.run(() -> {
            RawString rs = new RawString(name);
            setLongField(Header.TYPE_NAME, rs.getRegion().addr());
        }, this);
    }

    static String typeNameFromRegion(MemoryRegion region) {
        return new RawString(region).toString();
    }

     synchronized int getRefCount() {
        MemoryRegion reg = getPointer().region();
        return reg.getInt(Header.TYPE.getOffset(Header.REF_COUNT));
    }

    void incRefCount() {
        MemoryRegion reg = getPointer().region();
        Transaction.run(() -> {
            int oldCount = reg.getInt(Header.TYPE.getOffset(Header.REF_COUNT));
            reg.putInt(Header.TYPE.getOffset(Header.REF_COUNT), oldCount + 1);
            // trace(getPointer().addr(), "incRefCount(), type = %s, old = %d, new = %d",getPointer().type(), oldCount, getRefCount());
        }, this);
    }

    int decRefCount() {
        MemoryRegion reg = getPointer().region();
        Box<Integer> newCount = new Box<>();
        Transaction.run(() -> {
            int oldCount = reg.getInt(Header.TYPE.getOffset(Header.REF_COUNT));
            newCount.set(oldCount - 1);
            // trace(getPointer().addr(), "decRefCount, type = %s, old = %d, new = %d", getPointer().type(), oldCount, newCount.get());
            if (newCount.get() < 0) {
               trace(true, reg.addr(), "decRef below 0");
               new RuntimeException().printStackTrace(); System.exit(-1);}
            reg.putInt(Header.TYPE.getOffset(Header.REF_COUNT), newCount.get());
        }, this);
        return newCount.get();
    }

    void addReference() {
        Transaction.run(() -> {
            incRefCount();
            setColor(CycleCollector.BLACK);
        }, this);
    }

    synchronized void deleteReference() {
        Deque<Long> addrsToDelete = new ArrayDeque<>();
        MemoryRegion reg = getPointer().region();
        Transaction.run(() -> {
            int count = 0;
            int newCount = decRefCount();
            if (newCount == 0) {
                // trace(getPointer().addr(), "deleteReference, newCount == 0");
                addrsToDelete.push(getPointer().addr());
                while (!addrsToDelete.isEmpty()) {
                    long addrToDelete = addrsToDelete.pop();
                    Iterator<Long> childAddresses = getChildAddressIterator(addrToDelete);
                    ArrayList<PersistentObject> children = new ArrayList<>();
                    while (childAddresses.hasNext()) {
                        children.add(ObjectCache.get(childAddresses.next(), true));
                    }
                    // Transaction.run(() -> {
                    // }, children.toArray(new PersistentObject[0]));
                    for (PersistentObject child : children) {
                        Transaction.run(() -> {
                            long childAddr = child.getPointer().addr();
                            int crc = child.decRefCount();
                            if (crc == 0) {
                                addrsToDelete.push(childAddr);
                            } else {
                                CycleCollector.addCandidate(childAddr);
                            }
                        }, child);
                    }
                    free(addrToDelete);
                }
            } else {
                CycleCollector.addCandidate(getPointer().addr());
            }
        }, this);
    }

    static void deleteResidualReferences(long address, int count) {
        PersistentObject obj = ObjectCache.get(address, true);
        Transaction.run(() -> {
            int rc = obj.getRefCount();
            trace(address, "deleteResidualReferences %d, refCount = %d", count, obj.getRefCount());
            if (obj.getRefCount() < count) {
                trace(true, address, "refCount %d < count %d", obj.getRefCount(), count);
                System.exit(-1);
            }
            for (int i = 0; i < count - 1; i++) obj.decRefCount();
            obj.deleteReference();
        }, obj);
    }

    static String classNameForRegion(MemoryRegion reg) {
        long typeNameAddr = reg.getLong(0);
        MemoryRegion typeNameRegion = new UncheckedPersistentMemoryRegion(typeNameAddr);
        return PersistentObject.typeNameFromRegion(typeNameRegion);
    }

    static Iterator<Long> getChildAddressIterator(long address) {
        MemoryRegion reg = ObjectCache.get(address, true).getPointer().region();
        String typeName = classNameForRegion(reg);
        ObjectType<?> type = Types.typeForName(typeName);

        ArrayList<Long> childAddresses = new ArrayList<>();
        if (type instanceof ArrayType) {
            ArrayType<?> arrType = (ArrayType)type;
            if (arrType.getElementType() == Types.OBJECT) {
                int length = reg.getInt(ArrayType.LENGTH_OFFSET);
                for (int i = 0; i < length; i++) {
                    long childAddr = reg.getLong(arrType.getElementOffset(i));
                    if (childAddr != 0) {
                        childAddresses.add(childAddr);
                    }
                }
            }
        } else if (type instanceof ObjectType) {
            for (int i = Header.TYPE.fieldCount(); i < type.fieldCount(); i++) {
                List<PersistentType> types = type.getTypes();
                if (types.get(i) instanceof ObjectType || types.get(i) == Types.OBJECT) {
                    long childAddr = reg.getLong(type.getOffset(i));
                    if (childAddr != 0) {
                        childAddresses.add(childAddr);
                    }
                }
            }
        } else {
            throw new RuntimeException("getChildAddressIterator: unexpected type");
        }

        return childAddresses.iterator();
    }

    void setColor(byte color) {
        getPointer().region().putByte(Header.TYPE.getOffset(Header.REF_COLOR), color);
    }

    byte getColor() {
        return getPointer().region().getByte(Header.TYPE.getOffset(Header.REF_COLOR));
    }

    public static boolean monitorEnter(List<PersistentObject> toLock, List<PersistentObject> locked, boolean block) {
        trace("monitorEnter (lists), starting toLock = %d, locked = %d, block = %s", toLock.size(), locked.size(), block);
        // toLock.sort((x, y) -> Long.compare(x.getPointer().addr(), y.getPointer().addr()));
        boolean success = true;
        for (PersistentObject obj : toLock) {
            if (!block) {
                if (!obj.monitorEnterTimeout()) {
                    success = false;
                    // trace("TIMEOUT exceeded");
                    for(PersistentObject lockedObj : locked) {
                        lockedObj.monitorExit();
                        // trace("removed locked obj %d", obj.getPointer().addr());
                    }
                    locked.clear();
                    break;
                }
                else {
                    locked.add(obj);
                    // trace("added locked obj %d", obj.getPointer().addr());
                }
            }
            else obj.monitorEnter();
        }
        // trace("monitorEnter (lists), exiting toLock = %d, locked = %d", toLock.size(), locked.size());
        return success;
    }

    public void monitorEnter() {
        // trace(true, getPointer().addr(), "blocking monitorEnter for %s, attempt = %d", this, lib.xpersistent.XTransaction.tlInfo.get().attempts);
        UNSAFE.monitorEnter(this);
        // trace(true, getPointer().addr(), "blocking monitorEnter for %s exit", this);
    }

    public boolean monitorEnterTimeout(long timeout) {
        boolean success = false;
        long start = System.currentTimeMillis();
        int count = 0;
        do {
            success = UNSAFE.tryMonitorEnter(this);
            if (success) break;
            count++;
            Stats.locks.spinIterations++;
            // if (count > 2000) try {count = 0; Thread.sleep(1);} catch (InterruptedException ie) {ie.printStackTrace();}
        } while (System.currentTimeMillis() - start < timeout);  
        // if (success) Stats.locks.acquired++;
        // else Stats.locks.timeouts++;
        return success;
    }

    public boolean monitorEnterTimeout() {
        TransactionInfo info = lib.xpersistent.XTransaction.tlInfo.get();
        int max = info.timeout + random.nextInt(info.timeout);
        boolean success = monitorEnterTimeout(max);
        if (success) {
            info.timeout = Config.MONITOR_ENTER_TIMEOUT;
        }
        else {
            info.timeout = Math.min((int)(info.timeout * Config.MONITOR_ENTER_TIMEOUT_INCREASE_FACTOR), Config.MAX_MONITOR_ENTER_TIMEOUT);
        }
        return success;
    }

    public void monitorExit() {
        UNSAFE.monitorExit(this);
        // trace(getPointer().addr(), "released");
    }
}

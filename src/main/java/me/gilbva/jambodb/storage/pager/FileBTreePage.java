package me.gilbva.jambodb.storage.pager;

import me.gilbva.jambodb.storage.blocks.BlockStorage;
import me.gilbva.jambodb.storage.btrees.BTreePage;
import me.gilbva.jambodb.storage.btrees.Serializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileBTreePage<K, V> implements BTreePage<K, V> {
    private static final short FLAG_IS_LEAF = 1;

    private static final short FLAG_IS_FRAG = 2;

    private static final short FLAG_IS_DELETED = 4;

    private static final int FLAGS_POS = 0;

    private static final int SIZE_POS = 2;

    private static final int AD_POINTER_POS = 4;

    private static final int USED_BYTES_POS = 6;

    private static final int ELEMENTS_POS = 8;

    public static <K, V> FileBTreePage<K, V> create(BlockStorage storage, boolean isLeaf, Serializer<K> keySer, Serializer<V> valueSer) throws IOException {
        return new FileBTreePage<>(storage, isLeaf, keySer, valueSer);
    }

    public static <K, V> FileBTreePage<K, V> load(BlockStorage storage, int id, Serializer<K> keySer, Serializer<V> valueSer) throws IOException {
        return new FileBTreePage<>(storage, id, keySer, valueSer);
    }

    private final int id;

    private final ByteBuffer buffer;

    private final BlockStorage storage;

    private final Serializer<K> keySer;

    private final Serializer<V> valueSer;

    private final boolean leaf;

    private boolean modified;

    private Map<Short, ByteBuffer> overflowMap;

    private FileBTreePage(BlockStorage storage, int id, Serializer<K> keySer, Serializer<V> valueSer) throws IOException {
        this.id = id;
        this.storage = storage;
        this.keySer = keySer;
        this.valueSer = valueSer;

        this.buffer = ByteBuffer.allocate(BlockStorage.BLOCK_SIZE);
        this.storage.read(id-1, this.buffer);

        leaf = (flags() & FLAG_IS_LEAF) != 0;
    }

    private FileBTreePage(BlockStorage storage, boolean isLeaf, Serializer<K> keySer, Serializer<V> valueSer) throws IOException {
        this.storage = storage;
        this.keySer = keySer;
        this.valueSer = valueSer;

        this.id = storage.increase()+1;
        this.buffer = ByteBuffer.allocate(BlockStorage.BLOCK_SIZE);
        this.leaf = isLeaf;

        if(isLeaf) {
            flags(FLAG_IS_LEAF);
        }
        adPointer((short)BlockStorage.BLOCK_SIZE);
        usedBytes((short)0);
        size(0);
        modified = true;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public int size() {
        checkDeleted();
        return buffer.getShort(SIZE_POS);
    }

    @Override
    public void size(int value) {
        checkDeleted();
        int prevSize = size();

        if(prevSize == value) {
            return;
        }

        modified = true;
        if(prevSize < value) {
            buffer.putShort(SIZE_POS, (short) value);
            if(headerSize() > adPointer()) {
                defragment(prevSize);
            }

            for (int i = prevSize; i < value; i++) {
                resetElement(i);
            }
        }
        else {
            for (int i = value; i < prevSize; i++) {
                removeElement(i);
                resetElement(i);
            }
            buffer.putShort(SIZE_POS, (short) value);
        }
    }

    private void removeElement(int i) {
        removeData(keyPos(i), keySer);
        removeData(valuePos(i), valueSer);
    }

    private void resetElement(int i) {
        keyPos(i, (short) 0);
        valuePos(i, (short) 0);
        if (!leaf) {
            child(i + 1, 0);
        }
    }

    @Override
    public boolean isLeaf() {
        return leaf;
    }

    public boolean isModified() {
        return modified;
    }

    public boolean isDeleted() {
        return (flags() & FLAG_IS_DELETED) != 0;
    }

    public void setDeleted(boolean deleted) {
        if(deleted) {
            size(0);
            adPointer((short)BlockStorage.BLOCK_SIZE);
            usedBytes((short)0);
            overflowMap = null;
            flags((short) (flags() | FLAG_IS_DELETED));
        }
        else {
            flags((short) (flags() & ~FLAG_IS_FRAG));
        }
        modified = true;
    }

    public void save() throws IOException {
        if(!isDeleted()) {
            if (hasOverflow()) {
                defragment(size());
            }
            if (hasOverflow()) {
                throw new IOException("page overflow: usedBytes: " + usedBytes() + ", bodySize: " + bodySize());
            }
        }
        buffer.position(0);
        storage.write(id-1, buffer);
        modified = false;
    }

    @Override
    public boolean isFull() {
        checkDeleted();
        return usedBytes() >= bodySize();
    }

    @Override
    public boolean isHalf() {
        checkDeleted();
        return usedBytes() < (bodySize() / 4);
    }

    @Override
    public boolean canBorrow() {
        checkDeleted();
        return size() > 2 && usedBytes() > (bodySize() / 2);
    }

    @Override
    public K key(int index) {
        checkDeleted();
        if(index < 0 || index >= size()) {
            throw new IllegalArgumentException("invalid index=" + index + ", size=" + size());
        }
        return readData(keyPos(index), keySer);
    }

    @Override
    public void key(int index, K data) {
        checkDeleted();
        if(index < 0 || index >= size()) {
            throw new IllegalArgumentException("invalid index=" + index + ", size=" + size());
        }

        removeData(keyPos(index), keySer);
        keyPos(index, appendData(data, keySer));
        modified = true;
    }

    @Override
    public V value(int index) {
        checkDeleted();
        if(index < 0 || index >= size()) {
            throw new IllegalArgumentException("invalid index=" + index + ", size=" + size());
        }

        return readData(valuePos(index), valueSer);
    }

    @Override
    public void value(int index, V data) {
        checkDeleted();
        if(index < 0 || index >= size()) {
            throw new IllegalArgumentException("invalid index=" + index + ", size=" + size());
        }

        int pos = valuePos(index);
        removeData(pos, valueSer);
        valuePos(index, appendData(data, valueSer));
        modified = true;
    }

    @Override
    public void swap(int i, int j) {
        checkDeleted();
        short key = keyPos(i);
        short value = valuePos(i);

        keyPos(i, keyPos(j));
        valuePos(i, valuePos(j));

        keyPos(j, key);
        valuePos(j, value);
    }

    @Override
    public int child(int index) {
        checkDeleted();
        if(leaf) {
            throw new UnsupportedOperationException("child operations are not allowed on leaf pages");
        }
        if(index < 0 || index > size()) {
            throw new IllegalArgumentException("invalid index=" + index + ", size=" + size());
        }

        return buffer.getInt(elementPos(index));
    }

    @Override
    public void child(int index, int id) {
        checkDeleted();
        if(leaf) {
            throw new UnsupportedOperationException("child operations are not allowed on leaf pages");
        }
        if(index < 0 || index > size()) {
            throw new IllegalArgumentException("invalid index=" + index + ", size=" + size());
        }

        buffer.putInt(elementPos(index), id);
        modified = true;
    }

    private short flags() {
        return buffer.getShort(FLAGS_POS);
    }

    private void flags(short value) {
        buffer.putShort(FLAGS_POS, value);
    }

    private short adPointer() {
        checkDeleted();
        return buffer.getShort(AD_POINTER_POS);
    }

    private void adPointer(short value) {
        checkDeleted();
        buffer.putShort(AD_POINTER_POS, value);
    }

    public short usedBytes() {
        checkDeleted();
        return buffer.getShort(USED_BYTES_POS);
    }

    private void usedBytes(short value) {
        buffer.putShort(USED_BYTES_POS, value);
    }

    private short keyPos(int index) {
        int pos = leaf ? 0 : 4;
        return buffer.getShort(elementPos(index) + pos);
    }

    private void keyPos(int index, short value) {
        if(value >= BlockStorage.BLOCK_SIZE) {
            throw new IllegalArgumentException("invalid key pointer: " + value + " index=" + index);
        }
        int pos = leaf ? 0 : 4;
        buffer.putShort(elementPos(index) + pos, value);
    }

    private short valuePos(int index) {
        int relPos = leaf ? 2 : 6;
        int bytePos = elementPos(index) + relPos;
        return buffer.getShort(bytePos);
    }

    private void valuePos(int index, short value) {
        if(value >= BlockStorage.BLOCK_SIZE) {
            throw new IllegalArgumentException("invalid value pointer: " + value + " index=" + index);
        }
        int relPos = leaf ? 2 : 6;
        int buffPos = elementPos(index) + relPos;
        buffer.putShort(buffPos, value);
    }

    private <T> short appendData(T value, Serializer<T> ser) {
        int byteCount = ser.size(value);
        if(byteCount > BlockStorage.BLOCK_SIZE / 4) {
            throw new IllegalArgumentException("invalid data size");
        }

        short position = (short)(adPointer() - byteCount);
        if(position <= headerSize()) {
            position = overflow(value, ser);
        }
        else {
            buffer.position(position);
            ser.write(buffer, value);
            adPointer(position);
        }

        usedBytes((short) (usedBytes() + byteCount));
        return position;
    }

    private <T> T readData(int position, Serializer<T> ser) {
        if(position == 0 || position >= BlockStorage.BLOCK_SIZE) {
            throw new IllegalArgumentException("invalid position: " + position);
        }

        T result;
        if(position < 0) {
            ByteBuffer buffer = overflowMap.get((short) position);
            buffer.position(0);
            result = ser.read(buffer);
        }
        else {
            buffer.position(position);
            result = ser.read(buffer);
        }

        if(result == null) {
            throw new IllegalStateException("could not read correct value");
        }
        return result;
    }

    private <T> void removeData(int position, Serializer<T> ser) {
        if(position == 0) {
            return;
        }

        if(position > 0) {
            if (position < adPointer() || position >= BlockStorage.BLOCK_SIZE) {
                throw new IllegalArgumentException("invalid position: write position=" + position + ", adpointer=" + adPointer());
            }
        }

        int bytes;
        if(position < 0) {
            bytes = overflowMap.get((short) position).capacity();
            overflowMap.remove((short) position);
        }
        else {
            buffer.position(position);
            bytes = ser.size(buffer);
            setFragmented(true);
        }
        usedBytes((short) (usedBytes() - bytes));
    }

    private void defragment(int size) {
        List<K> keys = new ArrayList<>(size);
        List<V> values = new ArrayList<>(size);
        for(int i = 0; i < size; i++) {
            keys.add(readData(keyPos(i), keySer));
            values.add(readData(valuePos(i), valueSer));
        }
        usedBytes((short) 0);
        adPointer((short) BlockStorage.BLOCK_SIZE);
        overflowMap = null;
        for(int i = 0; i < size; i++) {
            keyPos(i, appendData(keys.get(i), keySer));
            valuePos(i, appendData(values.get(i), valueSer));
        }
        setFragmented(false);
    }

    private <T> short overflow(T value, Serializer<T> ser) {
        if(overflowMap == null) {
            overflowMap = new HashMap<>();
        }

        if(overflowMap.size() >= Short.MAX_VALUE) {
            throw new IllegalStateException("page data overflow");
        }

        int size = ser.size(value);
        ByteBuffer bb = ByteBuffer.allocate(size);
        ser.write(bb, value);
        var key = overflowMap.keySet()
                .stream()
                .min(Short::compareTo)
                .orElse((short)0);
        key--;
        if(key >= BlockStorage.BLOCK_SIZE) {
            throw new IllegalArgumentException("invalid appendData position generated: " + key);
        }
        overflowMap.put(key,  bb);
        return key;
    }

    private int elementPos(int index) {
        int elementSize = leaf ? 4 : 8;
        return ELEMENTS_POS + (index * elementSize);
    }

    private int headerSize() {
        int size = size();
        if(leaf) {
            return ELEMENTS_POS + (size * 4);
        }
        return ELEMENTS_POS + (size * 8) + 4;
    }

    private int bodySize() {
        return BlockStorage.BLOCK_SIZE - headerSize();
    }

    private boolean hasOverflow() {
        return overflowMap != null;
    }

    private void setFragmented(boolean isFrag) {
        if(isFrag) {
            flags((short) (flags() | FLAG_IS_FRAG));
        }
        else {
            flags((short) (flags() & ~FLAG_IS_FRAG));
        }
    }

    private boolean isFragmented() {
        return (flags() & FLAG_IS_FRAG) != 0;
    }

    private void checkDeleted() {
        if(isDeleted()) {
            throw new IllegalStateException("page " + id + " is deleted");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[");
        sb.append("#");
        sb.append(id());
        sb.append(" ");
        if(isDeleted()) {
            sb.append("*deleted* | ");
        }
        sb.append("size: ");
        sb.append(size());
        sb.append(" | used: ");
        sb.append(usedBytes());
        sb.append(" | body: ");
        sb.append(bodySize());
        sb.append(" | ");

        boolean first = true;
        for (int i = 0; i < size(); i++) {
            if (first) {
                first = false;
            }
            else {
                sb.append(", ");
            }

            sb.append(key(i));
            sb.append(": ");
            sb.append(value(i));

            if(!leaf) {
                sb.append(": ");
                sb.append(child(i));
            }
        }

        sb.append("]");
        return sb.toString();
    }
}
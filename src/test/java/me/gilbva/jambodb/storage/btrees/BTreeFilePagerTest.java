package me.gilbva.jambodb.storage.btrees;

import me.gilbva.jambodb.storage.pager.FilePager;
import me.gilbva.jambodb.storage.types.IntegerSerializer;
import me.gilbva.jambodb.storage.types.SmallStringSerializer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class BTreeFilePagerTest extends BTreeTestBase {
    @TestFactory
    public Collection<DynamicTest> testBTree() {
        List<DynamicTest> lst = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int size = i;
            lst.add(DynamicTest.dynamicTest("testing btree size=" + size, () -> doTest(size)));
        }

        for (int i = 10000; i < 100000; i += 10000) {
            final int size = i;
            lst.add(DynamicTest.dynamicTest("testing btree size=" + size, () -> doTest(size)));
        }

        return lst;
    }

    private void doTest(int size) throws IOException {
        var strToIntFile = Files.createTempFile("test", "jambodb");
        var intToStrFile = Files.createTempFile("test", "jambodb");

        var strToIntPager = FilePager.create(strToIntFile, SmallStringSerializer.INSTANCE, IntegerSerializer.INSTANCE);
        var intToStrPager = FilePager.create(intToStrFile, IntegerSerializer.INSTANCE, SmallStringSerializer.INSTANCE);
        performTest(size, strToIntPager, intToStrPager);

        removeAll(new BTree<>(strToIntPager));
        removeAll(new BTree<>(intToStrPager));

        strToIntPager = FilePager.open(strToIntFile, SmallStringSerializer.INSTANCE, IntegerSerializer.INSTANCE);
        intToStrPager = FilePager.open(intToStrFile, IntegerSerializer.INSTANCE, SmallStringSerializer.INSTANCE);
        performTest(size, strToIntPager, intToStrPager);
    }

    private void performTest(int size, FilePager<String, Integer> strToIntPager, FilePager<Integer, String> intToStrPager) throws IOException {
        var expectedStiTree = new TreeMap<String, Integer>();
        var expectedItsTree = new TreeMap<Integer, String>();

        var strToInt = new BTree<>(strToIntPager);
        var intToStr = new BTree<>(intToStrPager);

        for (int i = 0; i < size; i++) {
            var str = UUID.randomUUID().toString().substring(0, 8);

            expectedStiTree.put(str, i);
            expectedItsTree.put(i, str);
        }

        var strQueries = Arrays.asList(new String[][]{
                {"a", "z"},
                {"0", "9"}
        });

        var intQueries = Arrays.asList(new Integer[][]{
                {0, 5},
                {6, 9}
        });

        testBTree(expectedItsTree, intToStr, intQueries);
        testBTree(expectedStiTree, strToInt, strQueries);
    }

}
package com.glslsupport.lang;
import java.util.*;
public final class BlockLayout {
    public record Member(String path, int offset, int size, int alignment, int arrayStride, int matrixStride,
        String order, int padding) {}
    public record Block(String name, String packing, int size, int alignment, List<Member> members) {}
    private BlockLayout() {}
    public static List<Block> parse(String report) {
        var blocks = new LinkedHashMap<String, Block>();
        for (String line : report.lines().toList()) {
            var c = line.split("\t", -1);
            if (c.length == 5 && c[0].equals("block"))
                blocks.put(c[1],
                    new Block(c[1], c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]), new ArrayList<>()));
            else if (c.length == 10 && c[0].equals("member") && blocks.containsKey(c[1]))
                blocks.get(c[1]).members.add(
                    new Member(c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]), Integer.parseInt(c[5]),
                        Integer.parseInt(c[6]), Integer.parseInt(c[7]), c[8], Integer.parseInt(c[9])));
        }
        return blocks.values()
            .stream()
            .map(b -> new Block(b.name, b.packing, b.size, b.alignment, List.copyOf(b.members)))
            .toList();
    }
}

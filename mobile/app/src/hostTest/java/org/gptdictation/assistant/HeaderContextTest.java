package org.gptdictation.assistant;

/** Host-only checks for semantic labels grouped by clickable ancestor. */
public final class HeaderContextTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        HeaderContext one = new HeaderContext();
        one.add("menu-parent", "menu"); one.add("menu-parent", "menu");
        check(one.canonical() == null, "duplicate labels on one control do not make two anchors");
        one.add("edit-parent", "edit");
        HeaderContext two = new HeaderContext(); two.add("edit-parent", "edit"); two.add("menu-parent", "menu");
        check(one.canonical().equals(two.canonical()), "stable across tree traversal order");
        HeaderContext changed = new HeaderContext(); changed.add("menu-parent", "menu"); changed.add("edit-parent", "other");
        check(!one.canonical().equals(changed.canonical()), "semantic change must change context");
        HeaderContext extra = new HeaderContext(); extra.add("menu-parent", "menu"); extra.add("menu-parent", "menu-alt");
        check(extra.canonical() == null, "multiple labels on one ancestor are still one control");
        System.out.println("HeaderContext: ancestor deduplication and stable ordering passed");
    }
}

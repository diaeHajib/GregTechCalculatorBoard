package com.gtceu.calcboard.api.storage;

/**
 * Represents the functional type and structural isolation mode of a board page.
 */
public enum PageType {
    STANDARD,
    MODULE;

    /**
     * Safely parses a page type string without throwing exceptions.
     *
     * @param name     the name string to parse
     * @param fallback the default fallback type if the name is null or invalid
     * @return the matched PageType or fallback
     */
    public static PageType fromNameSafe(String name, PageType fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        for (PageType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return fallback;
    }
}

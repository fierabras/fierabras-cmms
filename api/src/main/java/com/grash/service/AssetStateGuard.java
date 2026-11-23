package com.grash.service;

import com.grash.exception.ConflictException;
import com.grash.model.Asset;

/**
 * P-14b (PPB-08, PPB-15): the single answer to "does this asset admit this change?". An archived asset admits no
 * change, to itself or to its children (downtimes, depreciation), only its restoration: 409 {@code ASSET_RETIRED}.
 * Every route that changes an asset or its children calls it after its 404 and 403 checks.
 */
public final class AssetStateGuard {

    public static final String ASSET_RETIRED = "ASSET_RETIRED";

    private static final String RETIRED_MESSAGE = "This asset is archived and cannot change. Restore it first";
    static final String NOT_ASSIGNABLE_MESSAGE = "This asset is archived and cannot be assigned";

    private AssetStateGuard() {
    }

    public static void check(Asset asset) {
        if (!admits(asset)) throw new ConflictException(ASSET_RETIRED, RETIRED_MESSAGE);
    }

    /**
     * Whether the asset admits a change, for the routes that skip it instead of rejecting the request (automatic
     * downtimes, jobs, automations).
     */
    public static boolean admits(Asset asset) {
        return asset == null || !asset.isArchived();
    }

    /**
     * The 409 for an archived asset given as a new reference.
     */
    static ConflictException notAssignable() {
        return new ConflictException(ASSET_RETIRED, NOT_ASSIGNABLE_MESSAGE);
    }
}

/*
 * Copyright (C) 2022 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <dlfcn.h>
#include <system/camera_metadata.h>
#include <cstdint>
#include <vector>

namespace {

bool appendKey(const void* data, size_t count, int32_t key, std::vector<int32_t>& keys) {
    if (count != 0 && data == nullptr) {
        return false;
    }

    const auto* original = static_cast<const int32_t*>(data);
    for (size_t i = 0; i < count; ++i) {
        if (original[i] == key) {
            return false;
        }
    }

    if (count != 0) {
        keys.assign(original, original + count);
    }
    keys.push_back(key);
    return true;
}

bool appendClientKey(const void* data, size_t count, std::vector<int32_t>& keys) {
    using QueryVendorTagLocation = int (*)(const char*, const char*, uint32_t*);
    static const auto queryVendorTag = reinterpret_cast<QueryVendorTagLocation>(
            dlsym(RTLD_DEFAULT, "_ZN4CamX16VendorTagManager22QueryVendorTagLocationEPKcS2_Pj"));

    uint32_t tag;
    if (queryVendorTag == nullptr ||
        queryVendorTag("com.meizu.device", "package_name", &tag) != 0) {
        return false;
    }

    return appendKey(data, count, static_cast<int32_t>(tag), keys);
}

bool hasEntryWithCount(camera_metadata_t* metadata, uint32_t tag, size_t elementCount) {
    camera_metadata_entry_t entry;
    return find_camera_metadata_entry(metadata, tag, &entry) == 0 && entry.count != 0 &&
           entry.count % elementCount == 0;
}

bool affectsUltraHighResolutionRequestKey(uint32_t tag) {
    switch (tag) {
        case ANDROID_REQUEST_AVAILABLE_REQUEST_KEYS:
        case ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION:
        case ANDROID_SCALER_AVAILABLE_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION:
        case ANDROID_SCALER_AVAILABLE_STALL_DURATIONS_MAXIMUM_RESOLUTION:
        case ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION:
        case ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION:
        case ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION:
        case ANDROID_SENSOR_INFO_BINNING_FACTOR:
            return true;
        default:
            return false;
    }
}

bool appendUltraHighResolutionRequestKey(
        camera_metadata_t* metadata, decltype(&update_camera_metadata_entry) updateMetadataEntry) {
    if (!hasEntryWithCount(metadata,
                           ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_MAXIMUM_RESOLUTION, 4) ||
        !hasEntryWithCount(metadata,
                           ANDROID_SCALER_AVAILABLE_MIN_FRAME_DURATIONS_MAXIMUM_RESOLUTION, 4) ||
        !hasEntryWithCount(metadata, ANDROID_SCALER_AVAILABLE_STALL_DURATIONS_MAXIMUM_RESOLUTION,
                           4) ||
        !hasEntryWithCount(metadata, ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION, 4) ||
        !hasEntryWithCount(metadata,
                           ANDROID_SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION,
                           4) ||
        !hasEntryWithCount(metadata, ANDROID_SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION, 2) ||
        !hasEntryWithCount(metadata, ANDROID_SENSOR_INFO_BINNING_FACTOR, 2)) {
        return false;
    }

    camera_metadata_entry_t requestKeys;
    if (find_camera_metadata_entry(metadata, ANDROID_REQUEST_AVAILABLE_REQUEST_KEYS,
                                   &requestKeys) != 0) {
        return false;
    }

    std::vector<int32_t> keys;
    if (appendKey(requestKeys.data.i32, requestKeys.count, ANDROID_SENSOR_PIXEL_MODE, keys)) {
        return updateMetadataEntry(metadata, requestKeys.index, keys.data(), keys.size(),
                                   nullptr) == 0;
    }
    return false;
}

}  // namespace

extern "C" int add_camera_metadata_entry(camera_metadata_t* dst, uint32_t tag, const void* data,
                                         size_t data_count) {
    static const auto original = reinterpret_cast<decltype(&add_camera_metadata_entry)>(
            dlsym(RTLD_NEXT, "add_camera_metadata_entry"));
    static const auto originalUpdate = reinterpret_cast<decltype(&update_camera_metadata_entry)>(
            dlsym(RTLD_NEXT, "update_camera_metadata_entry"));

    std::vector<int32_t> keys;
    const bool clientKeyAdded = (tag == ANDROID_REQUEST_AVAILABLE_REQUEST_KEYS ||
                                 tag == ANDROID_REQUEST_AVAILABLE_SESSION_KEYS) &&
                                appendClientKey(data, data_count, keys);

    int result = original(dst, tag, clientKeyAdded ? keys.data() : data,
                          clientKeyAdded ? keys.size() : data_count);
    if (result != 0 && clientKeyAdded) {
        result = original(dst, tag, data, data_count);
    }

    if (result == 0 && affectsUltraHighResolutionRequestKey(tag)) {
        appendUltraHighResolutionRequestKey(dst, originalUpdate);
    }
    return result;
}

extern "C" int update_camera_metadata_entry(camera_metadata_t* dst, size_t index, const void* data,
                                            size_t data_count,
                                            camera_metadata_entry_t* updated_entry) {
    static const auto original = reinterpret_cast<decltype(&update_camera_metadata_entry)>(
            dlsym(RTLD_NEXT, "update_camera_metadata_entry"));

    camera_metadata_entry_t entry;
    const bool foundEntry = get_camera_metadata_entry(dst, index, &entry) == 0;
    std::vector<int32_t> keys;
    const bool clientKeyAdded = foundEntry &&
                                (entry.tag == ANDROID_REQUEST_AVAILABLE_REQUEST_KEYS ||
                                 entry.tag == ANDROID_REQUEST_AVAILABLE_SESSION_KEYS) &&
                                appendClientKey(data, data_count, keys);

    int result = original(dst, index, clientKeyAdded ? keys.data() : data,
                          clientKeyAdded ? keys.size() : data_count, updated_entry);
    if (result != 0 && clientKeyAdded) {
        result = original(dst, index, data, data_count, updated_entry);
    }

    if (result == 0 && foundEntry && affectsUltraHighResolutionRequestKey(entry.tag)) {
        const bool requestKeysChanged = appendUltraHighResolutionRequestKey(dst, original);
        if (requestKeysChanged && updated_entry != nullptr) {
            get_camera_metadata_entry(dst, index, updated_entry);
        }
    }
    return result;
}

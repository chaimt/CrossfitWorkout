/* Copy one file into GARMIN/Apps on a Garmin watch that is speaking MTP. */
#include <libmtp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>

static void free_list(LIBMTP_file_t *list) {
    while (list != NULL) {
        LIBMTP_file_t *next = list->next;
        list->next = NULL;
        LIBMTP_destroy_file_t(list);
        list = next;
    }
}

static int name_is(const LIBMTP_file_t *file, const char *name) {
    return file->filename != NULL && strcasecmp(file->filename, name) == 0;
}

static LIBMTP_folder_t *find_folder(LIBMTP_folder_t *folder, const char *name) {
    for (; folder != NULL; folder = folder->sibling) {
        if (folder->name != NULL && strcasecmp(folder->name, name) == 0) {
            return folder;
        }
        LIBMTP_folder_t *found = find_folder(folder->child, name);
        if (found != NULL) {
            return found;
        }
    }
    return NULL;
}

static void free_folders(LIBMTP_folder_t *folder) {
    while (folder != NULL) {
        LIBMTP_folder_t *next = folder->sibling;
        free_folders(folder->child);
        folder->sibling = NULL;
        folder->child = NULL;
        LIBMTP_destroy_folder_t(folder);
        folder = next;
    }
}

static void fail(LIBMTP_mtpdevice_t *device, const char *message) {
    fprintf(stderr, "%s\n", message);
    if (device != NULL) {
        LIBMTP_Dump_Errorstack(device);
        LIBMTP_Clear_Errorstack(device);
        LIBMTP_Release_Device(device);
    }
    exit(1);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: mtp-send-prg <local.prg> <name-on-watch.prg>\n");
        return 2;
    }

    const char *local_path = argv[1];
    const char *remote_name = argv[2];
    struct stat st;
    if (stat(local_path, &st) != 0) {
        perror(local_path);
        return 1;
    }

    LIBMTP_Init();
    /* Get_First_Device() opens a cached session. Listing folders on that
       session fails with "tried to use LIBMTP_Get_Files_And_Folders on a
       cached device", so the watch has to be opened uncached. */
    LIBMTP_raw_device_t *raw = NULL;
    int raw_count = 0;
    LIBMTP_error_number_t detect = LIBMTP_Detect_Raw_Devices(&raw, &raw_count);
    int chosen = -1;
    if (detect == LIBMTP_ERROR_NONE && raw != NULL) {
        for (int i = 0; i < raw_count; i++) {
            if (raw[i].device_entry.vendor_id == 0x091e) {
                chosen = i;
                break;
            }
        }
    }
    if (chosen < 0) {
        free(raw);
        fprintf(stderr, "No MTP device found. Wake the watch and leave USB file transfer open.\n");
        return 1;
    }

    LIBMTP_mtpdevice_t *device = LIBMTP_Open_Raw_Device_Uncached(&raw[chosen]);
    free(raw);
    if (device == NULL) {
        fprintf(stderr, "Could not open the watch. Wake it and accept USB file transfer if it asks.\n");
        return 1;
    }
    if (LIBMTP_Get_Storage(device, LIBMTP_STORAGE_SORTBY_NOTSORTED) != 0 || device->storage == NULL) {
        fail(device, "Could not read the watch storage.");
    }

    /* Get_Files_And_Folders fails on the cached session this watch opens.
       The folder list still returns GARMIN/Apps. */
    LIBMTP_folder_t *folders = LIBMTP_Get_Folder_List(device);
    LIBMTP_folder_t *garmin = find_folder(folders, "GARMIN");
    LIBMTP_folder_t *apps = NULL;
    if (garmin != NULL) {
        for (LIBMTP_folder_t *child = garmin->child; child != NULL; child = child->sibling) {
            if (child->name != NULL &&
                (strcasecmp(child->name, "Apps") == 0 || strcasecmp(child->name, "APPS") == 0)) {
                apps = child;
                break;
            }
        }
    }
    uint32_t storage_id = 0;
    uint32_t apps_id = 0;
    if (apps != NULL) {
        apps_id = apps->folder_id;
        storage_id = apps->storage_id != 0 ? apps->storage_id : garmin->storage_id;
    }
    free_folders(folders);
    if (apps_id == 0) {
        fail(device, "GARMIN/Apps was not found on the watch.");
    }
    if (storage_id == 0 && device->storage != NULL) {
        storage_id = device->storage->id;
    }

    printf("Reading the watch file list...\n");
    LIBMTP_file_t *listing = LIBMTP_Get_Filelisting(device);

    uint32_t replace_ids[32];
    char replace_names[32][256];
    int replace_count = 0;
    if (apps_id != 0) {
        for (LIBMTP_file_t *existing = listing; existing != NULL; existing = existing->next) {
            if (existing->parent_id != apps_id || !name_is(existing, remote_name)) {
                continue;
            }
            if (replace_count >= 32) {
                break;
            }
            replace_ids[replace_count] = existing->item_id;
            snprintf(replace_names[replace_count], sizeof replace_names[0], "%s",
                     existing->filename != NULL ? existing->filename : remote_name);
            replace_count++;
        }
    }
    free_list(listing);

    if (apps_id == 0) {
        fail(device, "GARMIN/Apps was not found on the watch.");
    }

    for (int i = 0; i < replace_count; i++) {
        printf("Replacing %s\n", replace_names[i]);
        if (LIBMTP_Delete_Object(device, replace_ids[i]) != 0) {
            fail(device, "Could not remove the existing app file on the watch.");
        }
    }

    LIBMTP_file_t *file = LIBMTP_new_file_t();
    file->filesize = (uint64_t)st.st_size;
    file->filename = strdup(remote_name);
    file->filetype = LIBMTP_FILETYPE_UNKNOWN;
    file->parent_id = apps_id;
    file->storage_id = storage_id;
    if (file->filename == NULL) {
        fail(device, "Out of memory.");
    }

    printf("Copying %s (%lld bytes) to GARMIN/Apps/%s\n", local_path, (long long)st.st_size, remote_name);
    if (LIBMTP_Send_File_From_File(device, local_path, file, NULL, NULL) != 0) {
        fail(device, "Copy to the watch failed.");
    }
    printf("Installed %s on the watch.\n", remote_name);
    printf("Unplug the watch, then choose this watch face.\n");

    LIBMTP_destroy_file_t(file);
    LIBMTP_Release_Device(device);
    return 0;
}

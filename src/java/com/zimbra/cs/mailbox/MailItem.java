/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2004, 2005, 2006, 2007, 2008, 2009, 2010, 2011 Zimbra, Inc.
 *
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.mailbox;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.zimbra.common.mailbox.Color;
import com.zimbra.common.mailbox.ContactConstants;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ByteUtil;
import com.zimbra.common.util.Pair;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.SystemUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.db.DbMailItem;
import com.zimbra.cs.db.DbTag;
import com.zimbra.cs.index.SortBy;
import com.zimbra.cs.index.IndexDocument;
import com.zimbra.cs.mailbox.MailItem.CustomMetadata.CustomMetadataList;
import com.zimbra.cs.mailbox.util.TypedIdList;
import com.zimbra.cs.session.PendingModifications;
import com.zimbra.cs.session.Session;
import com.zimbra.cs.session.PendingModifications.Change;
import com.zimbra.cs.store.MailboxBlob;
import com.zimbra.cs.store.StagedBlob;
import com.zimbra.cs.store.StoreManager;

/**
 * @since Aug 12, 2004
 */
public abstract class MailItem implements Comparable<MailItem> {

    public enum Type {
        UNKNOWN(-1),
        /** Item is a standard {@link Folder}. */
        FOLDER(1),
        /** Item is a saved search {@link SearchFolder}. */
        SEARCHFOLDER(2),
        /** Item is a user-created {@link Tag}. */
        TAG(3),
        /** Item is a real, persisted {@link Conversation}. */
        CONVERSATION(4),
        /** Item is a mail {@link Message}. */
        MESSAGE(5),
        /** Item is a {@link Contact}. */
        CONTACT(6),
        /** Item is a {@link InviteMessage} with a {@code text/calendar} MIME part. */
        @Deprecated INVITE(7),
        /** Item is a bare {@link Document}. */
        DOCUMENT(8),
        /** Item is a {@link Note}. */
        NOTE(9),
        /** Item is a memory-only system {@link Flag}. */
        FLAG(10),
        /** Item is a calendar {@link Appointment}. */
        APPOINTMENT(11),
        /** Item is a memory-only, 1-message {@link VirtualConversation}. */
        VIRTUAL_CONVERSATION(12),
        /** Item is a {@link Mountpoint} pointing to a {@link Folder}, possibly in another user's {@link Mailbox}. */
        MOUNTPOINT(13),
        /** Item is a {@link WikiItem} */
        @Deprecated WIKI(14),
        /** Item is a {@link Task} */
        TASK(15),
        /** Item is a {@link Chat} */
        CHAT(16),
        /** Item is a {@link Comment} */
        COMMENT(17),
        /** Item is a {@link Link} pointing to a {@link Document} */
        LINK(18);

        private static final Map<Byte, Type> BYTE2TYPE;
        static {
            ImmutableMap.Builder<Byte, Type> builder = ImmutableMap.builder();
            for (Type type : Type.values()) {
                builder.put(type.toByte(), type);
            }
            BYTE2TYPE = builder.build();
        }

        private final byte btype;

        private Type(int b) {
            btype = (byte) b;
        }

        public byte toByte() {
            return btype;
        }

        /**
         * Returns the human-readable name (e.g. "tag") for the item type.
         */
        @Override
        public String toString() {
            return name().toLowerCase();
        }

        public static String toString(Set<Type> types) {
            return Joiner.on(',').skipNulls().join(types);
        }

        public static Type of(byte b) {
            Type result = BYTE2TYPE.get(b);
            return result != null ? result : UNKNOWN;
        }

        /**
         * Returns the item type for the specified human-readable type name.
         *
         * @param name string representation of a type
         * @return type
         */
        public static Type of(String name) {
            if (Strings.isNullOrEmpty(name)) {
                return UNKNOWN;
            }
            try {
                return Type.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                if ("briefcase".equalsIgnoreCase(name)) { // synonym of document
                    return DOCUMENT;
                } else {
                    return UNKNOWN;
                }
            }
        }

        /**
         * Parses a CSV of type names.
         *
         * @param csv comma-separated types
         * @return set of types
         * @throws IllegalArgumentException if the CSV contains an invalid type name
         */
        public static Set<Type> setOf(String csv) {
            Set<Type> result = EnumSet.noneOf(Type.class);
            for (String token : Splitter.on(',').trimResults().split(csv)) {
                Type type = Type.of(token);
                if (type != UNKNOWN) {
                    result.add(type);
                } else {
                    throw new IllegalArgumentException(token);
                }
            }
            return result;
        }

        public boolean isLeafNode() {
            switch (this) {
                case FOLDER:
                case SEARCHFOLDER:
                case MOUNTPOINT:
                case FLAG:
                case TAG:
                case CONVERSATION:
                case VIRTUAL_CONVERSATION:
                case UNKNOWN:
                    return false;
                default:
                    return true;
            }
        }
    }

    public static final int FLAG_UNCHANGED = 0x80000000;
    public static final int MAX_FLAG_COUNT = 31;

    public static final String[] TAG_UNCHANGED = null;

    public static final byte DEFAULT_COLOR = 0;
    public static final Color DEFAULT_COLOR_RGB = new Color(DEFAULT_COLOR);

    public enum IndexStatus {
        /** Not indexable. */
        NO(-1),
        /** Not indexed yet (add only). */
        DEFERRED(0),
        /** Not re-indexed yet (delete & add). */
        STALE(1),
        /** Indexed. */
        DONE(Integer.MAX_VALUE);

        private final int id;

        private IndexStatus(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        static IndexStatus of(int id) {
            switch (id) {
                case -1:
                    return NO;
                case 0:
                    return DEFERRED;
                case 1:
                    return STALE;
                default:
                    return DONE;
            }
        }
    }

    public static final class UnderlyingData implements Cloneable {
        public int id;
        public byte type;
        public int parentId = -1;
        public int folderId = -1;
        public int indexId  = IndexStatus.NO.id();
        public int imapId   = -1;
        public String locator;
        private String blobDigest;
        public int date;
        public long size;
        public int unreadCount;
        private int flags;
        private String[] tags = NO_TAGS;
        private String subject;
        public String name;
        public String metadata;
        public int modMetadata;
        public int dateChanged;
        public int modContent;

        public String getSubject() {
            return subject;
        }

        public UnderlyingData setSubject(String value) {
            this.subject = DbMailItem.normalize(value, DbMailItem.MAX_SUBJECT_LENGTH);
            return this;
        }

        /** Returns the item's blob digest, or <tt>null</tt> if the item has no blob. */
        public String getBlobDigest() {
            return blobDigest;
        }

        public UnderlyingData setBlobDigest(String digest) {
            this.blobDigest = "".equals(digest) ? null : digest;
            return this;
        }

        public boolean isUnread() {
            return (unreadCount > 0);
        }

        public int getFlags() {
            return flags;
        }

        public UnderlyingData setFlag(Flag flag) {
            return setFlags(flags | flag.toBitmask());
        }

        public UnderlyingData setFlag(Flag.FlagInfo flag) {
            return setFlags(flags | flag.toBitmask());
        }

        public UnderlyingData unsetFlag(Flag flag) {
            return setFlags(flags & ~flag.toBitmask());
        }

        public UnderlyingData unsetFlag(Flag.FlagInfo flag) {
            return setFlags(flags & ~flag.toBitmask());
        }

        /** Sets all flags to the values specified in the given bit field. */
        public UnderlyingData setFlags(int bitfield) {
            assert (bitfield & ~Flag.FLAGS_ALL) == 0 : "Invalid flag bitfield: " + bitfield;
            this.flags = bitfield & Flag.FLAGS_ALL;
            return this;
        }

        public boolean isSet(Flag.FlagInfo flag) {
            return flag != null && (flags & flag.toBitmask()) != 0;
        }

        private static final String[] NO_TAGS = new String[0];

        public UnderlyingData setTags(Tag.NormalizedTags ntags) {
            this.tags = ntags == null ? NO_TAGS : ntags.getTags();
            return this;
        }

        public String[] getTags() {
            return tags;
        }

        UnderlyingData duplicate(int newId, int newFolder, String newLocator) {
            UnderlyingData data = new UnderlyingData();
            data.id = newId;
            data.type = this.type;
            data.parentId = this.parentId;
            data.folderId = newFolder;
            data.indexId = this.indexId;
            data.imapId = this.imapId <= 0 ? this.imapId : newId;
            data.locator = newLocator;
            data.blobDigest = this.blobDigest;
            data.date = this.date;
            data.size = this.size;
            data.flags = this.flags;
            data.tags = this.tags;
            data.subject = this.subject;
            data.unreadCount = this.unreadCount;
            return data;
        }

        @Override
        protected UnderlyingData clone() {
            try {
                return (UnderlyingData) super.clone();
            } catch (CloneNotSupportedException cnse) {
                return null;
            }
        }

        void metadataChanged(Mailbox mbox) throws ServiceException {
            modMetadata = mbox.getOperationChangeID();
            dateChanged = mbox.getOperationTimestamp();
            if (!isAcceptableType(Type.FOLDER, Type.of(type)) && !isAcceptableType(Type.TAG, Type.of(type))) {
                mbox.getFolderById(folderId).updateHighestMODSEQ();
            }
        }

        void contentChanged(Mailbox mbox) throws ServiceException {
            metadataChanged(mbox);
            modContent = modMetadata;
        }

        private static final String FN_ID           = "id";
        private static final String FN_TYPE         = "tp";
        private static final String FN_PARENT_ID    = "pid";
        private static final String FN_FOLDER_ID    = "fid";
        private static final String FN_INDEX_ID     = "idx";
        private static final String FN_IMAP_ID      = "imap";
        private static final String FN_LOCATOR      = "loc";
        private static final String FN_BLOB_DIGEST  = "dgst";
        private static final String FN_DATE         = "dt";
        private static final String FN_SIZE         = "sz";
        private static final String FN_UNREAD_COUNT = "uc";
        private static final String FN_FLAGS        = "fg";
        private static final String FN_TAGS         = "tg";
        private static final String FN_SUBJECT      = "sbj";
        private static final String FN_NAME         = "nm";
        private static final String FN_METADATA     = "meta";
        private static final String FN_MOD_METADATA = "modm";
        private static final String FN_MOD_CONTENT  = "modc";
        private static final String FN_DATE_CHANGED = "dc";

        Metadata serialize() {
            Metadata meta = new Metadata();
            meta.put(FN_ID, id);
            meta.put(FN_TYPE, type);
            meta.put(FN_PARENT_ID, parentId);
            meta.put(FN_FOLDER_ID, folderId);
            meta.put(FN_INDEX_ID, indexId);
            meta.put(FN_IMAP_ID, imapId);
            meta.put(FN_LOCATOR, locator);
            meta.put(FN_BLOB_DIGEST, blobDigest);
            meta.put(FN_DATE, date);
            meta.put(FN_SIZE, size);
            meta.put(FN_UNREAD_COUNT, unreadCount);
            meta.put(FN_FLAGS, flags);
            meta.put(FN_TAGS, DbTag.serializeTags(tags));
            meta.put(FN_SUBJECT, subject);
            meta.put(FN_NAME, name);
            meta.put(FN_METADATA, metadata);
            meta.put(FN_MOD_METADATA, modMetadata);
            meta.put(FN_MOD_CONTENT, modContent);
            meta.put(FN_DATE_CHANGED, dateChanged);
            return meta;
        }

        void deserialize(Metadata meta) throws ServiceException {
            this.id = (int) meta.getLong(FN_ID, 0);
            this.type = (byte) meta.getLong(FN_TYPE, 0);
            this.parentId = (int) meta.getLong(FN_PARENT_ID, -1);
            this.folderId = (int) meta.getLong(FN_FOLDER_ID, -1);
            this.indexId = meta.getInt(FN_INDEX_ID, IndexStatus.NO.id());
            this.imapId = (int) meta.getLong(FN_IMAP_ID, -1);
            this.locator = meta.get(FN_LOCATOR, null);
            this.blobDigest = meta.get(FN_BLOB_DIGEST, null);
            this.date = (int) meta.getLong(FN_DATE, 0);
            this.size = meta.getLong(FN_SIZE, 0);
            this.unreadCount = (int) meta.getLong(FN_UNREAD_COUNT, 0);
            setFlags((int) meta.getLong(FN_FLAGS, 0));
            // are the tags ever non-null? we're assuming that they aren't...
            setTags(new Tag.NormalizedTags(DbTag.deserializeTags(meta.get(FN_TAGS, null))));
            this.subject = meta.get(FN_SUBJECT, null);
            this.name = meta.get(FN_NAME, null);
            this.metadata = meta.get(FN_METADATA, null);
            this.modMetadata = (int) meta.getLong(FN_MOD_METADATA, 0);
            this.modContent = (int) meta.getLong(FN_MOD_CONTENT, 0);
            this.dateChanged = (int) meta.getLong(FN_DATE_CHANGED, 0);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("id", id).add("type", Type.of(type)).toString();
        }
    }

    public static final class TargetConstraint {
        public static final short INCLUDE_TRASH  = 0x01;
        public static final short INCLUDE_SPAM   = 0x02;
        public static final short INCLUDE_SENT   = 0x04;
        public static final short INCLUDE_OTHERS = 0x08;
        public static final short INCLUDE_QUERY  = 0x10;
        private static final short ALL_LOCATIONS = INCLUDE_TRASH | INCLUDE_SPAM | INCLUDE_SENT | INCLUDE_OTHERS;

        private static final char ENC_TRASH = 't';
        private static final char ENC_SPAM  = 'j';
        private static final char ENC_SENT  = 's';
        private static final char ENC_OTHER = 'o';
        private static final char ENC_QUERY = 'q';

        private short  inclusions;
        private String query;

        private Mailbox mailbox;
        private int     sentFolder = -1;

        public TargetConstraint(Mailbox mbox, short include) {
            this(mbox, include, null);
        }

        public TargetConstraint(Mailbox mbox, String includeQuery) {
            this(mbox, INCLUDE_QUERY, includeQuery);
        }

        public TargetConstraint(Mailbox mbox, short include, String includeQuery) {
            mailbox = mbox;
            if (includeQuery == null || includeQuery.trim().length() == 0) {
                inclusions = (short) (include & ~INCLUDE_QUERY);
            } else {
                inclusions = (short) (include | INCLUDE_QUERY);
                query = includeQuery;
            }
        }

        public static TargetConstraint parseConstraint(Mailbox mbox, String encoded) throws ServiceException {
            if (encoded == null)
                return null;

            boolean invert = false;
            short inclusions = 0;
            String query = null;
            loop: for (int i = 0; i < encoded.length(); i++) {
                switch (encoded.charAt(i)) {
                    case ENC_TRASH:  inclusions |= INCLUDE_TRASH;       break;
                    case ENC_SPAM:   inclusions |= INCLUDE_SPAM;        break;
                    case ENC_SENT:   inclusions |= INCLUDE_SENT;        break;
                    case ENC_OTHER:  inclusions |= INCLUDE_OTHERS;      break;
                    case ENC_QUERY:  inclusions |= INCLUDE_QUERY;
                                     query = encoded.substring(i + 1);  break loop;
                    case '-':  if (i == 0 && encoded.length() > 1)  { invert = true;  break; }
                        // fall through...
                    default:  throw ServiceException.INVALID_REQUEST("invalid encoded constraint: " + encoded, null);
                }
            }
            if (invert) {
                inclusions ^= ALL_LOCATIONS;
            }
            return new TargetConstraint(mbox, inclusions, query);
        }

        @Override
        public String toString() {
            if (inclusions == 0)
                return "";

            StringBuilder sb = new StringBuilder();
            if ((inclusions & INCLUDE_TRASH) != 0)   sb.append(ENC_TRASH);
            if ((inclusions & INCLUDE_SPAM) != 0)    sb.append(ENC_SPAM);
            if ((inclusions & INCLUDE_SENT) != 0)    sb.append(ENC_SENT);
            if ((inclusions & INCLUDE_OTHERS) != 0)  sb.append(ENC_OTHER);
            if ((inclusions & INCLUDE_QUERY) != 0)   sb.append(ENC_QUERY).append(query);
            return sb.toString();
        }

        public static boolean checkItem(TargetConstraint tcon, MailItem item) throws ServiceException {
            return (tcon == null ? true : tcon.checkItem(item));
        }

        private boolean checkItem(MailItem item) throws ServiceException {
            // FIXME: doesn't support EXCLUDE_QUERY
            if ((inclusions & ALL_LOCATIONS) == 0)
                return false;
            if ((inclusions & INCLUDE_TRASH) != 0 && item.inTrash())
                return true;
            if ((inclusions & INCLUDE_SPAM) != 0 && item.inSpam())
                return true;
            if ((inclusions & INCLUDE_SENT) != 0 && inSent(item))
                return true;
            if ((inclusions & INCLUDE_OTHERS) != 0 && !item.inTrash() && !item.inSpam() && !inSent(item))
                return true;
            return false;
        }

        /** Returns whether an item is in the user's sent folder.  Returns
         *  <tt>false</tt> if the user has set their sent folder to be
         *  any folder other than the default "/Sent" folder, folder 5.<p>
         *
         *  The reason we don't just compare the item's folder against the
         *  user's configured sent folder is that when the user sets their
         *  sent folder to be "/Inbox", *all* Inbox messages will be skipped
         *  when the "sent" folder is excluded via tcon, which is not what
         *  we want.  See bug 3972 for details. */
        private boolean inSent(MailItem item) {
            // only count as "in sent" if the item's in the real "/Sent" folder
            if (item.getFolderId() != Mailbox.ID_FOLDER_SENT)
                return false;

            if (sentFolder == -1) {
                sentFolder = Mailbox.ID_FOLDER_SENT;
                try {
                    String sent = mailbox.getAccount().getAttr(Provisioning.A_zimbraPrefSentMailFolder, null);
                    if (sent != null) {
                        sentFolder = mailbox.getFolderByPath(null, sent).getId();
                    }
                } catch (ServiceException e) { }
            }
            // only count as "in sent" if the user's sent folder is 5 and
            //   the item's in there
            return sentFolder == Mailbox.ID_FOLDER_SENT && sentFolder == item.getFolderId();
            // return sentFolder == item.getFolderId();
        }
    }

    public static final class CustomMetadata extends HashMap<String, String> {
        private static final long serialVersionUID = -3866150929202858077L;

        private final String mSectionKey;
        private String mSerializedValue;

        public CustomMetadata(String section) {
            this(section, null);
        }

        public CustomMetadata(String section, String serialized) {
            super(8);
            mSectionKey = section.trim();
            mSerializedValue = serialized;
        }

        static CustomMetadata deserialize(Pair<String, String> serialized) throws ServiceException {
            CustomMetadata custom = new CustomMetadata(serialized.getFirst());
            for (Map.Entry<String, ?> entry : new Metadata(serialized.getSecond()).asMap().entrySet()) {
                custom.put(entry.getKey(), entry.getValue().toString());
            }
            return custom;
        }

        public String getSectionKey() {
            return mSectionKey;
        }

        public String getSerializedValue() {
            if (mSerializedValue != null) {
                return mSerializedValue;
            }
            remove(null);
            return new Metadata(this).toString();
        }

        @Override
        public String toString() {
            return mSectionKey + ": " + super.toString();
        }

        public CustomMetadataList asList() {
            return isEmpty() ? null : new CustomMetadataList(this);
        }

        public static final class CustomMetadataList extends ArrayList<Pair<String, String>> {
            private static final long serialVersionUID = 3213399133413270157L;

            public CustomMetadataList() {
                super(1);
            }
            public CustomMetadataList(CustomMetadata custom) {
                this();
                addSection(custom);
            }

            public void addSection(CustomMetadata custom) {
                if (custom.isEmpty()) {
                    removeSection(custom.getSectionKey());
                } else {
                    addSection(custom.getSectionKey(), custom.getSerializedValue());
                }
            }

            public void addSection(String key, String encoded) {
                removeSection(key);
                if (key != null && encoded != null) {
                    add(new Pair<String, String>(key, encoded));
                }
            }

            public CustomMetadata getSection(String key) throws ServiceException {
                if (!isEmpty()) {
                    for (Pair<String, String> entry : this) {
                        if (key.equals(entry.getFirst())) {
                            return CustomMetadata.deserialize(entry);
                        }
                    }
                }
                return null;
            }

            public List<String> listSections() {
                List<String> sections = new ArrayList<String>(size());
                for (Pair<String, String> entry : this) {
                    sections.add(entry.getFirst());
                }
                return sections;
            }

            public void removeSection(String key) {
                if (key != null && !isEmpty()) {
                    for (Iterator<Pair<String, String>> it = iterator(); it.hasNext(); ) {
                        if (key.equals(it.next().getFirst())) {
                            it.remove();
                        }
                    }
                }
            }

            public long guessSize() {
                long size = 0;
                if (!isEmpty()) {
                    for (Pair<String, String> entry : this) {
                        size += entry.getFirst().length() + entry.getSecond().length();
                    }
                }
                return size;
            }
        }
    }

    protected int            mId;
    protected UnderlyingData mData;
    protected Mailbox        mMailbox;
    protected MailboxBlob    mBlob;
    protected int            mVersion;
    protected List<MailItem> mRevisions;
    protected Color          mRGBColor;          // 8 bits each for red, green, and blue.
                                                 // if highest byte is zero it's old style
                                                 // color map with 9 fixed colors.
    protected CustomMetadataList mExtendedData;

    MailItem(Mailbox mbox, UnderlyingData data) throws ServiceException {
        if (data == null) {
            throw new IllegalArgumentException();
        }
        mId      = data.id;
        mData    = data;
        mMailbox = mbox;
        decodeMetadata(mData.metadata);
        checkItemCreationAllowed(); // this check may rely on decoded metadata
        mData.metadata = null;

        if ((data.getFlags() & Flag.BITMASK_UNCACHED) == 0) {
            mbox.cache(this); // store the item in the mailbox's cache
        }
    }

    protected void checkItemCreationAllowed() throws ServiceException {
        // not allowed in external account mailbox
        if (getAccount().isIsExternalVirtualAccount()) {
            throw ServiceException.PERM_DENIED("permission denied for external account");
        }
    }

    /** Returns the item's ID.  IDs are unique within a {@link Mailbox} and
     *  are assigned in increasing (though not necessarily gap-free) order. */
    public int getId() {
        return mData.id;
    }

    /** Returns the item's type. */
    public Type getType() {
        return Type.of(mData.type);
    }

    /** Returns the numeric ID of the {@link Mailbox} this item belongs to. */
    public int getMailboxId() {
        return mMailbox.getId();
    }

    /** Returns the {@link Mailbox} this item belongs to. */
    public Mailbox getMailbox() {
        return mMailbox;
    }

    /** Returns the {@link Account} this item's Mailbox belongs to. */
    public Account getAccount() throws ServiceException {
        return mMailbox.getAccount();
    }

    /** Returns the item's color.  If not specified, defaults to
     *  {@link #DEFAULT_COLOR}.  No "color inheritance" (e.g. from the
     *  item's folder or tags) is performed. */
    public byte getColor() {
        return mRGBColor.getMappedColor();
    }

    /** Returns the item's color represented in RGB. */
    public Color getRgbColor() {
        return mRGBColor;
    }

    /** Returns the item's name.  If the item doesn't have a name (e.g.
     *  messages, contacts, appointments), returns <tt>""</tt>.
     *  If not <tt>""</tt>, this name should be unique across all item
     *  types within the parent folder. */
    public String getName() {
        return mData.name == null ? "" : StringUtil.trimTrailingSpaces(mData.name);
    }

    /** Returns the ID of the item's parent.  Not all items have parents;
     *  some that do include {@link Message} (parent is {@link Conversation})
     *  and {@link Folder} (parent is Folder). */
    public int getParentId() {
        return mData.parentId;
    }

    /** Returns the ID of the {@link Folder} the item lives in.  All items
     *  must have a non-<tt>null</tt> folder. */
    public int getFolderId() {
        return mData.folderId;
    }

    /** Returns the path to the MailItem.  If the item is in a hidden folder
     *  or is of a type that does not have a name (e.g. {@link Message}s,
     *  {@link Contact}s, etc.), this method returns <tt>null</tt>. */
    public String getPath() throws ServiceException {
        String path = getFolder().getPath(), name = getName();
        if (name == null || path == null)
            return null;
        return path + (path.endsWith("/") ? "" : "/") + name;
    }

    /** Returns the ID the item is referenced by in the index.  Returns -1
     *  for non-indexed items.  For indexed items, the "index ID" will be the
     *  same as the item ID unless the item is a copy of another item; in that
     *  case, the "index ID" is the same as the original item's "index ID". */
    public int getIndexId() {
        return mData.indexId;
    }

    public IndexStatus getIndexStatus() {
        return IndexStatus.of(mData.indexId);
    }

    /** Returns the UID the item is referenced by in the IMAP server.  Returns
     *  <tt>0</tt> for items that require renumbering because of moves.
     *  The "IMAP UID" will be the same as the item ID unless the item has
     *  been moved after the mailbox owner's first IMAP session. */
    public int getImapUid() {
        return mData.imapId;
    }

    /** Returns the ID of the {@link Volume} the item's blob is stored on.
     *  Returns <tt>null</tt> for items that have no stored blob. */
    public String getLocator() {
        return mData.locator;
    }

    /** Returns the SHA-1 hash of the item's uncompressed blob.
     *
     * @return the blob digest, or <tt>null</tt> if no blob exists */
    public String getDigest() {
        return mData.getBlobDigest();
    }

    /** Returns the 1-based version number on the item.  Each time the item's
     *  "content" changes (e.g. editing a {@link Document} or a draft), this
     *  counter is incremented. */
    public int getVersion() {
        return mVersion;
    }

    /** Returns the date the item's content was last modified.  For immutable
     *  objects (e.g. received messages), this will be the same as the date
     *  the item was created. */
    public long getDate() {
        return mData.date * 1000L;
    }

    /** Returns the change ID corresponding to the last time the item's
     *  content was modified.  For immutable objects (e.g. received messages),
     *  this will be the same change ID as when the item was created. */
    public int getSavedSequence() {
        return mData.modContent;
    }

    /** Returns the date the item's metadata and/or content was last modified.
     *  This includes changes in tags and flags as well as folder-to-folder
     *  moves and recoloring. */
    public long getChangeDate() {
        return mData.dateChanged * 1000L;
    }

    /** Returns the change ID corresponding to the last time the item's
     *  metadata and/or content was modified.  This includes changes in tags
     *  and flags as well as folder-to-folder moves and recoloring. */
    public int getModifiedSequence() {
        return mData.modMetadata;
    }

    /** Returns the item's size as it counts against mailbox quota.  For items
     *  that have a blob, this is the size in bytes of the raw blob. */
    public long getSize() {
        return mData.size;
    }

    /** Returns the item's total count against mailbox quota including all old
     *  revisions.  For items that have a blob, this is the sum of the size in
     *  bytes of the raw blobs. */
    public long getTotalSize() throws ServiceException {
        long size = mData.size;
        if (isTagged(Flag.FlagInfo.VERSIONED)) {
            for (MailItem revision : loadRevisions()) {
                size += revision.getSize();
            }
        }
        return size;
    }

    public String getSubject() {
        return Strings.nullToEmpty(mData.getSubject());
    }

    /** Returns the item's underlying storage data so that it may be persisted
     *  somewhere besides the database - usually in encoded form. */
    public UnderlyingData getUnderlyingData() {
        mData.metadata = encodeMetadata().toString();
        return mData;
    }

    public abstract String getSender();

    /** Returns the SORT-FORM (maybe truncated, etc.) of the subject of this mail item. */
    public String getSortSubject() {
        return getSubject();
    }

    /** Returns the SORT-FORM (maybe truncated) of the sender of this mail item. */
    public String getSortSender() {
        return getSender();
    }

    /** Returns the SORT-FORM (maybe truncated) of the recipients of this mail item. */
    public String getSortRecipients() {
        return null;
    }

    /** Returns the "external" flag bitmask, which includes
     *  {@link Flag#BITMASK_UNREAD} when the item is unread. */
    public int getFlagBitmask() {
        int flags = mData.getFlags();
        if (isUnread()) {
            flags = flags | Flag.BITMASK_UNREAD;
        }
        return flags;
    }

    public List<String> getCustomDataSections() {
        if (mExtendedData == null || mExtendedData.isEmpty())
            return Collections.emptyList();
        return mExtendedData.listSections();
    }

    /** Returns the requested set of non-Zimbra-standard metadata values in
     *  the requested {@code section}.  If no set of custom metadata is
     *  associated with the {@code section}, returns <tt>null</tt>.
     * @see #setCustomData(CustomMetadata) */
    public CustomMetadata getCustomData(String section) throws ServiceException {
        if (section == null || mExtendedData == null) {
            return null;
        }
        return mExtendedData.getSection(section);
    }

    private static final int TOTAL_METADATA_LIMIT = 10000;

    /** Updates the requested set of non-Zimbra-standard metadata values in
     *  the requested section.  If the provided set of {@code custom}
     *  metdata contains no metadata key/value pairs, the section is deleted.
     * @see #getCustomData(String) */
    void setCustomData(CustomMetadata custom) throws ServiceException {
        if (custom == null) {
            return;
        }
        if (!canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the necessary permissions on the item");
        }

        markItemModified(Change.METADATA);
        // first add the new section to the list
        if (mExtendedData != null) {
            mExtendedData.addSection(custom);
        } else if (!custom.isEmpty()) {
            mExtendedData = custom.asList();
        }
        // then check to make sure we're not overflowing our limit
        if (mExtendedData != null && !custom.isEmpty() && mExtendedData.guessSize() > TOTAL_METADATA_LIMIT) {
            throw MailServiceException.TOO_MUCH_METADATA(TOTAL_METADATA_LIMIT);
        }
        // and finally write the new data to the database
        saveMetadata();
    }


    /** Returns the "internal" flag bitmask, which does not include
     *  {@link Flag#BITMASK_UNREAD} and {@link Flag#BITMASK_IN_DUMPSTER}.
     *  This is the same bitmask as is stored in the database's {@code
     *  MAIL_ITEM.FLAGS} column. */
    public int getInternalFlagBitmask() {
        return mData.getFlags() & ~Flag.BITMASK_IN_DUMPSTER;
    }

    /** Returns the external string representation of this item's flags.
     *  This string includes the state of {@link Flag#BITMASK_UNREAD} and is
     *  formed by concatenating the appropriate {@link Flag#FLAG_REP}
     *  characters for all flags set on the item. */
    public String getFlagString() {
        long flags = mData.getFlags();
        if (flags == 0) {
            return isUnread() ? Flag.UNREAD_FLAG_ONLY : "";
        } else {
            return Flag.toString((int) (flags | (isUnread() ? Flag.BITMASK_UNREAD : 0)));
        }
    }

    public String[] getTags() {
        String[] tags = mData.getTags(), copy = tags.length == 0 ? tags : new String[tags.length];
        System.arraycopy(tags, 0, copy, 0, tags.length);
        return copy;
    }

    @Deprecated
    public List<Integer> getTagIds() {
        String[] tags = mData.getTags();
        if (tags.length == 0) {
            return Collections.emptyList();
        }

        List<Integer> tagIds = new ArrayList<Integer>(tags.length);
        for (String tag : tags) {
            try {
                tagIds.add(mMailbox.getTagByName(tag).getId());
            } catch (ServiceException e) { }
        }
        return tagIds;
    }

    public boolean isTagged(Flag.FlagInfo finfo) {
        return mData.isSet(finfo);
    }

    public boolean isTagged(Tag tag) {
        if (tag instanceof Flag) {
            return (mData.getFlags() & ((Flag) tag).toBitmask()) != 0;
        } else {
            return Arrays.asList(mData.getTags()).contains(tag.getName());
        }
    }

    public boolean isTagged(String tagName) {
        if (StringUtil.isNullOrEmpty(tagName)) {
            return false;
        } else if (tagName.startsWith(Tag.FLAG_NAME_PREFIX)) {
            return mData.isSet(Flag.FlagInfo.of(tagName));
        } else {
            return Arrays.asList(mData.getTags()).contains(tagName);
        }
    }

    /** Returns whether the given flag bitmask applies to the object.<p>
     *
     *  Equivalent to {@code ((getFlagBitmask() & <b>mask</b>) != 0)}. */
    boolean isFlagSet(long mask) {
        return ((getFlagBitmask() & mask) != 0);
    }

    /** Returns whether the item's unread count is >0.
     * @see #getUnreadCount() */
    public boolean isUnread() {
        return mData.unreadCount > 0;
    }

    /** Returns the item's unread count.  For "leaf items", this will be either
     *  <tt>0</tt> or <tt>1</tt>; for aggregates like {@link Folder}s and
     *  {@link Tag}s and {@link Conversation}s, it's the total number of unread
     *  aggregated "leaf items".  {@link Mountpoint}s will always have an
     *  unread count of <tt>0</tt>. */
    public int getUnreadCount() {
        return mData.unreadCount;
    }

    public boolean isFlagged() {
        return isTagged(Flag.FlagInfo.FLAGGED);
    }

    public boolean hasAttachment() {
        return isTagged(Flag.FlagInfo.ATTACHED);
    }

    /** Returns whether the item is in the "main mailbox", i.e. not in the
     *  Junk or Trash folders.  Items in subfolders of Trash are considered
     *  to be in the Trash and hence not "inMailbox".
     *
     * @throws ServiceException on errors fetching the item's folder.
     * @see #inTrash
     * @see #inSpam */
    public boolean inMailbox() throws ServiceException {
        return !inSpam() && !inTrash();
    }

    /** Returns whether the item is in the Trash folder or any of its
     *  subfolders.
     *
     * @throws ServiceException on errors fetching the item's folder. */
    public boolean inTrash() throws ServiceException {
        if (mData.folderId <= Mailbox.HIGHEST_SYSTEM_ID) {
            return (mData.folderId == Mailbox.ID_FOLDER_TRASH);
        }
        Folder folder = mMailbox.getFolderById(null, getFolderId());
        return folder.inTrash();
    }

    /** Returns whether the item is in the Junk folder.  (The Junk folder
     *  may not have subfolders.) */
    public boolean inSpam() {
        return mData.folderId == Mailbox.ID_FOLDER_SPAM;
    }

    public boolean inDumpster() {
        return (mData.getFlags() & Flag.BITMASK_IN_DUMPSTER) != 0;
    }


    /** Returns whether the caller has the requested access rights on this
     *  item.  The owner of the {@link Mailbox} has all rights on all items
     *  in the Mailbox, as do all admin accounts.  All other users must be
     *  explicitly granted access.  <i>(Tag sharing and negative rights not
     *  yet implemented.)</i>  The authenticated user is fetched from the
     *  transaction's {@link OperationContext} via a call to
     *  {@link Mailbox#getAuthenticatedAccount}.
     *
     * @param rightsNeeded  A set of rights (e.g. {@link ACL#RIGHT_READ}
     *                      and {@link ACL#RIGHT_DELETE}).
     * @throws ServiceException on errors fetching LDAP entries or
     *         retrieving the item's folder
     * @see ACL
     * @see Folder#checkRights(short, Account, boolean) */
    boolean canAccess(short rightsNeeded) throws ServiceException {
        return canAccess(rightsNeeded, mMailbox.getAuthenticatedAccount(), mMailbox.isUsingAdminPrivileges());
    }

    /** Returns whether the specified account has the requested access rights
     *  on this item.  The owner of the {@link Mailbox} has all rights on all
     *  items in the Mailbox, as do all admin accounts.  All other users must
     *  be explicitly granted access.  <i>(Tag sharing and negative rights not
     *  yet implemented.)</i>
     *
     * @param rightsNeeded  A set of rights (e.g. {@link ACL#RIGHT_READ}
     *                      and {@link ACL#RIGHT_DELETE}).
     * @param authuser      The user whose rights we need to query.
     * @param asAdmin       Whether to use admin priviliges (if any).
     * @throws ServiceException on errors fetching LDAP entries or
     *         retrieving the item's folder
     * @see ACL
     * @see Folder#canAccess(short) */
    boolean canAccess(short rightsNeeded, Account authuser, boolean asAdmin) throws ServiceException {
        if (rightsNeeded == 0) {
            return true;
        }
        return checkRights(rightsNeeded, authuser, asAdmin) == rightsNeeded;
    }

    /** Returns the subset of the requested access rights that the user has
     *  been granted on this item.  The owner of the {@link Mailbox} has
     *  all rights on all items in the Mailbox, as do all admin accounts.
     *  All other users must be explicitly granted access.  <i>(Tag sharing
     *  and negative rights not yet implemented.)</i>
     *
     * @param rightsNeeded  A set of rights (e.g. {@link ACL#RIGHT_READ}
     *                      and {@link ACL#RIGHT_DELETE}).
     * @param authuser      The user whose rights we need to query.
     * @param asAdmin       Whether to use admin priviliges (if any).
     * @see ACL
     * @see Folder#checkRights(short, Account, boolean) */
    short checkRights(short rightsNeeded, Account authuser, boolean asAdmin) throws ServiceException {
        // check to see what access has been granted on the enclosing folder
        Folder folder = !inDumpster() ? getFolder() : getMailbox().getFolderById(Mailbox.ID_FOLDER_TRASH);
        short granted = folder.checkRights(rightsNeeded, authuser, asAdmin);
        // FIXME: check to see what access has been granted on the item's tags
        //   granted |= getTags().getGrantedRights(rightsNeeded, authuser);
        // and see if the granted rights are sufficient
        return (short) (granted & rightsNeeded);
    }


    /** Returns the {@link MailboxBlob} corresponding to the item's on-disk
     *  representation.  If the item is memory- or database-only, returns
     *  <tt>null</tt>.
     *
     * @throws MailServiceException.NO_SUCH_BLOB if the file cannot be found.
     * @throws ServiceException
     * */
    public synchronized MailboxBlob getBlob() throws ServiceException {
        if (mBlob == null && getDigest() != null) {
            mBlob = StoreManager.getInstance().getMailboxBlob(this);
            if (mBlob == null) {
                throw MailServiceException.NO_SUCH_BLOB(mMailbox.getId(), mId, mData.modContent);
            }
        }
        return mBlob;
    }

    /** Returns an {@link InputStream} of the raw, uncompressed content of
     *  the message.  This is the message body as received via SMTP; no
     *  postprocessing has been performed to make opaque attachments (e.g.
     *  TNEF) visible.
     *
     * @return The data stream, or <tt>null</tt> if the item has no blob
     * @throws ServiceException when the message file does not exist.
     * @see #getMimeMessage()
     * @see #getContent() */
    public InputStream getContentStream() throws ServiceException {
        if (getDigest() == null) {
            return null;
        }

        try {
            MailboxBlob mblob = getBlob();
            if (mblob == null) {
                throw ServiceException.FAILURE("missing blob for id: " + getId() + ", change: " + getModifiedSequence(), null);
            }
            return StoreManager.getInstance().getContent(mblob);
        } catch (IOException e) {
            String msg = String.format("Unable to get content for %s %d", getClass().getSimpleName(), getId());
            throw ServiceException.FAILURE(msg, e);
        }
    }

    /** Returns the raw, uncompressed content of the item's blob as a byte
     *  array.  For messages, this is the message body as received via SMTP;
     *  no postprocessing has been performed to make opaque attachments
     *  (e.g. TNEF) visible.  When possible, this content is cached in the
     *
     * @return The blob content, or <tt>null</tt> if the item has no blob.
     * @throws ServiceException when the blob file does not exist.
     * @see #getMimeMessage()
     * @see #getContentStream() */
    public byte[] getContent() throws ServiceException {
        if (getDigest() == null) {
            return null;
        }

        try {
            return ByteUtil.getContent(getContentStream(), (int) getSize());
        } catch (IOException e) {
            throw ServiceException.FAILURE("Unable to get content for item " + getId(), e);
        }
    }

    @Override public int compareTo(MailItem that) {
        if (this == that) {
            return 0;
        }
        return mId - that.getId();
    }

    public static final class SortIdAscending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            return m1.getId() - m2.getId();
        }
    }

    public static final class SortIdDescending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            return m2.getId() - m1.getId();
        }
    }

    public static final class SortModifiedSequenceAscending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            return m1.getModifiedSequence() - m2.getModifiedSequence();
        }
    }

    public static final class SortDateAscending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            long t1 = m1.getDate(), t2 = m2.getDate();

            if (t1 < t2)        return -1;
            else if (t1 == t2)  return 0;
            else                return 1;
        }
    }

    public static final class SortDateDescending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            long t1 = m1.getDate(), t2 = m2.getDate();

            if (t1 < t2)        return 1;
            else if (t1 == t2)  return 0;
            else                return -1;
        }
    }

    public static final class SortSizeAscending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            long t1 = m1.getSize(), t2 = m2.getSize();

            if (t1 < t2)        return -1;
            else if (t1 == t2)  return 0;
            else                return 1;
        }
    }

    public static final class SortSizeDescending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            long t1 = m1.getSize(), t2 = m2.getSize();

            if (t1 < t2)        return 1;
            else if (t1 == t2)  return 0;
            else                return -1;
        }
    }

    public static final class SortImapUid implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            return m1.getImapUid() - m2.getImapUid();
        }
    }

    public static final class SortSubjectAscending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            return m1.getSubject().compareToIgnoreCase(m2.getSubject());
        }
    }

    public static final class SortSubjectDescending implements Comparator<MailItem> {
        @Override public int compare(MailItem m1, MailItem m2) {
            return -m1.getSubject().compareToIgnoreCase(m2.getSubject());
        }
    }

    public static abstract class SortNameNaturalOrder implements Comparator<MailItem> {
        private static class Name {
            public char[] buf;
            public int    pos;
            public int    len;

            public Name(String n) {
                buf = n.toCharArray();
                pos = 0;
                len = buf.length;
            }

            public char getChar() {
                if (pos < len)
                    return buf[pos];
                return 0;
            }

            public Name next() {
                if (pos < len)
                    pos++;
                return this;
            }
        }

        @Override
        public int compare(MailItem m1, MailItem m2) {
            if (m1.getName() == null) {
                return returnResult(1);
            } else if (m2.getName() == null) {
                return returnResult(-1);
            }
            return compareString(new Name(m1.getName()), new Name(m2.getName()));
        }

        public int compareString(Name n1, Name n2) {
            char first = n1.getChar();
            char second = n2.getChar();

            if (isDigit(first) && isDigit(second)) {
                return compareNumeric(n1, n2);
            } else if (first != second) {
                return returnResult(first - second);
            } else if (first == 0 && second == 0) {
                return 0;
            }

            return compareString(n1.next(), n2.next());
        }

        public int compareNumeric(Name n1, Name n2) {
            int firstNum = readInt(n1);
            int secondNum = readInt(n2);

            if (firstNum != secondNum) {
                return returnResult(firstNum - secondNum);
            }

            return compareString(n1.next(), n2.next());
        }

        public int readInt(Name n) {
            int start = n.pos;
            int end = 0;
            while (isDigit(n.getChar())) {
                n.next();
            }
            end = n.pos;
            if (end == start) {
                return 0;
            }
            try {
                return Integer.parseInt(new String(n.buf, start, end - start));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public boolean isDigit(char c) {
            return Character.isDigit(c);
        }

        protected abstract int returnResult(int result);
    }

    public static final class SortNameNaturalOrderAscending extends SortNameNaturalOrder {
        @Override protected int returnResult(int result) {
            return result;
        }
    }

    public static final class SortNameNaturalOrderDescending extends SortNameNaturalOrder {
        @Override protected int returnResult(int result) {
            return -result;
        }
    }

    static Comparator<MailItem> getComparator(SortBy sort) {
        boolean asc = sort.getDirection() == SortBy.Direction.ASC;
        switch (sort.getKey()) {
            case ID:
                return asc ? new SortIdAscending() : new SortIdDescending();
            case DATE:
                return asc ? new SortDateAscending() : new SortDateDescending();
            case SIZE:
                return asc ? new SortSizeAscending() : new SortSizeDescending();
            case SUBJECT:
                return asc ? new SortSubjectAscending() : new SortSubjectDescending();
            case NAME_NATURAL_ORDER:
                return asc ? new SortNameNaturalOrderAscending() : new SortNameNaturalOrderDescending();
            default:
                return null;
        }
    }

    /**
     * This class intentionally does not inherit from ServiceException, the
     *  exception is internal-only and should never be exposed outside of this package.
     */
    static class TemporaryIndexingException extends Exception {
        private static final long serialVersionUID = 730987946876783701L;
    }

    /**
     * Returns the indexable data to be passed into index. Subclasses that support indexing must override.
     * <p>
     * This API is generally to be called WITHOUT the Mailbox lock is held -- it is the implementation's responsibility
     * to lock the mailbox if that is necessary to get a consistent snapshot.
     *
     * @return a list of Lucene Documents to be added to the index for this item
     * @throws TemporaryIndexingException recoverable index error
     */
    public List<IndexDocument> generateIndexData() throws TemporaryIndexingException {
        return null;
    }

    /** Returns the item's parent.  Returns <tt>null</tt> if the item
     *  does not have a parent.
     *
     * @throws ServiceException if there is an error retrieving the
     *         Mailbox's item cache or fetching the parent's data from
     *         the database. */
    MailItem getParent() throws ServiceException {
        if (mData.parentId == -1 || inDumpster())
            return null;
        return mMailbox.getItemById(mData.parentId, Type.UNKNOWN);
    }

    /** Returns the item's {@link Folder}.  All items in the system must
     *  have a containing folder.
     *
     * @throws ServiceException should never be thrown, as the set of all
     *                          folders must already be cached. */
    Folder getFolder() throws ServiceException {
        return mMailbox.getFolderById(mData.folderId);
    }

    abstract boolean isTaggable();
    abstract boolean isCopyable();
    abstract boolean isMovable();
    abstract boolean isMutable();
    abstract boolean canHaveChildren();
    boolean isDeletable()             { return true; }
    boolean isLeafNode()              { return true; }
    boolean trackUnread()             { return true; }
    boolean canParent(MailItem child) { return canHaveChildren(); }


    static MailItem getById(Mailbox mbox, int id) throws ServiceException {
        return getById(mbox, id, Type.UNKNOWN);
    }

    static MailItem getById(Mailbox mbox, int id, Type type) throws ServiceException {
        return getById(mbox, id, type, false);
    }

    static MailItem getById(Mailbox mbox, int id, Type type, boolean fromDumpster) throws ServiceException {
        return mbox.getItem(DbMailItem.getById(mbox, id, type, fromDumpster));
    }

    static List<MailItem> getById(Mailbox mbox, Collection<Integer> ids, Type type) throws ServiceException {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<MailItem> items = new ArrayList<MailItem>();
        for (UnderlyingData ud : DbMailItem.getById(mbox, ids, type)) {
            items.add(mbox.getItem(ud));
        }
        return items;
    }

    static MailItem getByImapId(Mailbox mbox, int id, int folderId) throws ServiceException {
        return mbox.getItem(DbMailItem.getByImapId(mbox, id, folderId));
    }

    /** Instantiates the appropriate subclass of <tt>MailItem</tt> for
     *  the item described by the {@link MailItem.UnderlyingData}.  Will
     *  not create memory-only <tt>MailItem</tt>s like {@link Flag}
     *  and {@link VirtualConversation}.
     *
     * @param mbox  The {@link Mailbox} the item is created in.
     * @param data  The contents of a <tt>MAIL_ITEM</tt> database row. */
    public static MailItem constructItem(Mailbox mbox, UnderlyingData data) throws ServiceException {
        if (data == null) {
            throw noSuchItem(-1, Type.UNKNOWN);
        }
        switch (Type.of(data.type)) {
            case FOLDER:       return new Folder(mbox, data);
            case SEARCHFOLDER: return new SearchFolder(mbox, data);
            case TAG:          return new Tag(mbox, data);
            case CONVERSATION: return new Conversation(mbox,data);
            case MESSAGE:      return new Message(mbox, data);
            case CONTACT:      return new Contact(mbox,data);
            case DOCUMENT:     return new Document(mbox, data);
            case NOTE:         return new Note(mbox, data);
            case APPOINTMENT:  return new Appointment(mbox, data);
            case TASK:         return new Task(mbox, data);
            case MOUNTPOINT:   return new Mountpoint(mbox, data);
            case WIKI:         return new WikiItem(mbox, data);
            case CHAT:         return new Chat(mbox, data);
            case COMMENT:      return new Comment(mbox, data);
            default:           return null;
        }
    }

    /** Returns {@link MailServiceException.NoSuchItemException} tailored
     *  for the given type.  Does not actually <u>throw</u> the exception;
     *  that's the caller's job.
     *
     * @param id    The id of the missing item.
     * @param type  The type of the missing item (e.g. {@link #TYPE_TAG}). */
    public static MailServiceException noSuchItem(int id, Type type) {
        switch (type) {
            case SEARCHFOLDER:
            case MOUNTPOINT:
            case FOLDER:
                return MailServiceException.NO_SUCH_FOLDER(id);
            case FLAG:
            case TAG:
                return MailServiceException.NO_SUCH_TAG(id);
            case VIRTUAL_CONVERSATION:
            case CONVERSATION:
                return MailServiceException.NO_SUCH_CONV(id);
            case CHAT:
            case MESSAGE:
                return MailServiceException.NO_SUCH_MSG(id);
            case CONTACT:
                return MailServiceException.NO_SUCH_CONTACT(id);
            case WIKI:
            case DOCUMENT:
                return MailServiceException.NO_SUCH_DOC(id);
            case NOTE:
                return MailServiceException.NO_SUCH_NOTE(id);
            case APPOINTMENT:
                return MailServiceException.NO_SUCH_APPT(id);
            case TASK:
                return MailServiceException.NO_SUCH_TASK(id);
            default:
                return MailServiceException.NO_SUCH_ITEM(id);
        }
    }

    /**
     * Returns whether an item type is a "subclass" of another item type.
     * <p>
     * For instance, returns {@code true} if you have an item of {@link Type#FLAG} and you wanted things of
     * {@link Type#TAG}. The exception to this rule is that a desired {@link Type#UNKNOWN} matches any actual item type.
     *
     * @param desired  The type of item that you wanted.
     * @param actual   The type of item that you've got.
     * @return {@code true} if the types match, if {@code desired} is {@link Type#UNKNOWN}, or if the {@code actual}
     * class is a subclass of the {@code desired} class.
     */
    public static boolean isAcceptableType(Type desired, Type actual) {
        // standard case: exactly what we're asking for
        if (desired == actual || desired == Type.UNKNOWN) {
            return true;
        // exceptions: ask for Tag and get Flag, ask for Folder and get SearchFolder or Mountpoint,
        //             ask for Conversation and get VirtualConversation, ask for Document and get Wiki
        } else if (desired == Type.FOLDER && actual == Type.SEARCHFOLDER) {
            return true;
        } else if (desired == Type.FOLDER && actual == Type.MOUNTPOINT) {
            return true;
        } else if (desired == Type.TAG && actual == Type.FLAG) {
            return true;
        } else if (desired == Type.CONVERSATION && actual == Type.VIRTUAL_CONVERSATION) {
            return true;
        } else if (desired == Type.DOCUMENT && actual == Type.WIKI) {
            return true;
        } else if (desired == Type.MESSAGE && actual == Type.CHAT) {
            return true;
        // failure: found something, but it's not the type you were looking for
        } else {
            return false;
        }
    }

    /** Returns whether the item is a "subclass" of another item type.  For
     *  instance, returns <tt>true</tt> if the item is a {@link Flag} and you
     *  wanted things of type {@link #TYPE_TAG}.  The exception to this rule
     *  is that a desired {@link #TYPE_UNKNOWN} matches any actual item type.
     *
     * @param desired  The type of item that you wanted.
     * @return <tt>true</tt> if the types match, if <tt>desired</tt> is
     *         {@link #TYPE_UNKNOWN}, or if the item is a subclass of the
     *         <tt>desired</tt> class. */
    public boolean isAcceptableType(Type desired) {
        return isAcceptableType(desired, getType());
    }

    boolean checkChangeID() throws ServiceException {
        return mMailbox.checkItemChangeID(this);
    }

    /** Adds this item to the {@link Mailbox}'s list of items created during
     *  the transaction. */
    void markItemCreated() {
        mMailbox.markItemCreated(this);
    }

    /** Adds this item to the {@link Mailbox}'s list of items deleted during
     *  the transaction. */
    void markItemDeleted() {
        mMailbox.markItemDeleted(this);
    }

    /** Adds this item to the {@link Mailbox}'s list of items modified during
     *  the transaction.
     *
     * @param reason  The bitmask of changes made to the item.
     * @see PendingModifications.Change */
    void markItemModified(int reason) {
        try {
            mMailbox.markItemModified(this, reason, snapshotItem());
        } catch (ServiceException e) {
            ZimbraLog.mailbox.warn("error cloning item with id %s", mId, e);
        }
    }

    /** Adds this item to the {@link Mailbox}'s list of blobs to be removed
     *  upon <u>successful</u> completion of the current transaction. */
    void markBlobForDeletion() {
        try {
            markBlobForDeletion(getBlob());
        } catch (ServiceException e) {
            ZimbraLog.mailbox.warn("error queuing blob for deletion for id: " + mId + ", change: " + mData.modContent, e);
        }
    }

    /** Adds this {@link MailboxBlob} to the {@link Mailbox}'s list of blobs
     *  to be removed upon <u>successful</u> completion of the current
     *  transaction. */
    void markBlobForDeletion(MailboxBlob mblob) {
        if (mblob == null)
            return;
        PendingDelete info = new PendingDelete();
        info.blobs.add(mblob);
        mMailbox.markOtherItemDirty(info);
    }

    /** Updates various lists and counts as the result of item creation.  This
     *  method should always be called immediately after a new item is created
     *  and persisted to the database.
     *
     * @param parent  The created item's parent.  The parent's addChild()
     *                method will be called during the function. */
    protected void finishCreation(MailItem parent) throws ServiceException {
        markItemCreated();

        // let the parent know it's got a new child
        if (parent != null) {
            parent.addChild(this);
        }

        // sanity-check the location of the newly-created item
        Folder folder = getFolder();
        if (!folder.canContain(this)) {
            throw MailServiceException.CANNOT_CONTAIN();
        }

        // update mailbox and folder sizes
        if (isLeafNode()) {
            boolean isDeleted = isTagged(Flag.FlagInfo.DELETED);

            mMailbox.updateSize(mData.size, isQuotaCheckRequired());
            folder.updateSize(1, isDeleted ? 1 : 0, mData.size);
            updateTagSizes(1, isDeleted ? 1 : 0, mData.size);

            // let the folder and tags know if the new item is unread
            folder.updateUnread(mData.unreadCount, isDeleted ? mData.unreadCount : 0);
            updateTagUnread(mData.unreadCount, isDeleted ? mData.unreadCount : 0);
        }
    }

    /**
     * Returns {@code true} if a quota check is required when creating
     * this item.  See bug 15666.
     */
    @SuppressWarnings("unused")
    protected boolean isQuotaCheckRequired() throws ServiceException {
        return true;
    }

    /** Changes the item's color.  Color is specified in RGB, with
     *  one byte each for red, blue, and green.  The highest byte
     *  is unused.
     *
     * @param color  The item's new color.
     * @perms {@link ACL#RIGHT_WRITE} on the item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul> */
    void setColor(Color color) throws ServiceException {
        if (!canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the necessary permissions on the item");
        } else if (color.equals(mRGBColor)) {
            return;
        }
        markItemModified(Change.COLOR);
        mRGBColor.set(color);
        saveMetadata();
    }

    /** Changes the item's color.  The server does no value-to-color mapping;
     *  the supplied color is treated as an opaque byte.  Note than even
     *  "immutable" items can have their color changed.
     *
     * @param color  The item's new color.
     * @perms {@link ACL#RIGHT_WRITE} on the item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul> */
    @Deprecated
    void setColor(byte color) throws ServiceException {
        if (!canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the necessary permissions on the item");
        } else if (color == mRGBColor.getMappedColor()) {
            return;
        }
        markItemModified(Change.COLOR);
        mRGBColor.setColor(color);
        saveMetadata();
    }

    /** Changes the item's date.
     *
     * @param date  The item's new date.
     * @perms {@link ACL#RIGHT_WRITE} on the item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul> */
    void setDate(long date) throws ServiceException {
        if (mData.date == date) {
            return;
        } else if (!canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the necessary permissions on the item");
        }
        markItemModified(Change.DATE);
        mData.date = (int)(date / 1000L);
        mData.metadataChanged(mMailbox);
        if (ZimbraLog.mailop.isDebugEnabled()) {
            ZimbraLog.mailop.debug("Setting date of %s to %d.", getMailopContext(this), date);
        }
        DbMailItem.saveDate(this);
    }

    /** Sets the IMAP UID for the item and persists it to the database.  Does
     *  not update the containing folder's IMAP UID highwater mark; that is
     *  done implicitly whenever the folder size increases. */
    void setImapUid(int imapId) throws ServiceException {
        if (mData.imapId == imapId) {
            return;
        }
        if (ZimbraLog.mailop.isDebugEnabled()) {
            ZimbraLog.mailop.debug("Setting imapId of %s to %d.", getMailopContext(this), imapId);
        }
        markItemModified(Change.IMAP_UID);
        mData.imapId = imapId;
        mData.metadataChanged(mMailbox);
        DbMailItem.saveImapUid(this);

        getFolder().updateUIDNEXT();
    }

    MailboxBlob setContent(StagedBlob staged, Object content) throws ServiceException, IOException {
        addRevision(false);

        // update the item's relevant attributes
        markItemModified(Change.CONTENT  | Change.DATE | Change.IMAP_UID | Change.SIZE);

        // delete the old blob *unless* we've already rewritten it in this transaction
        if (getSavedSequence() != mMailbox.getOperationChangeID()) {
            if (!canAccess(ACL.RIGHT_WRITE)) {
                throw ServiceException.PERM_DENIED("you do not have the necessary permissions on the item");
            }
            boolean delete = true;
            // don't delete blob if last revision uses it
            if (isTagged(Flag.FlagInfo.VERSIONED)) {
                List<MailItem> revisions = loadRevisions();
                if (!revisions.isEmpty()) {
                    MailItem lastRev = revisions.get(revisions.size() - 1);
                    if (lastRev.getSavedSequence() == getSavedSequence()) {
                        delete = false;
                    }
                }
            }
            if (delete) {
                markBlobForDeletion();
            }
        }

        // remove the content from the cache
        MessageCache.purge(this);

        // update the object to reflect its new contents
        long size = staged == null ? 0 : staged.getSize();
        if (mData.size != size) {
            mMailbox.updateSize(size - mData.size, isQuotaCheckRequired());
            mData.size = size;
        }
        getFolder().updateSize(0, 0, size - mData.size);

        mData.setBlobDigest(staged == null ? null : staged.getDigest());
        mData.date   = mMailbox.getOperationTimestamp();
        mData.imapId = mMailbox.isTrackingImap() ? 0 : mData.id;
        mData.contentChanged(mMailbox);

        // write the content (if any) to the store
        MailboxBlob mblob = null;
        if (staged != null) {
            StoreManager sm = StoreManager.getInstance();
            // under windows, a rename will fail if the incoming file is open
            if (SystemUtil.ON_WINDOWS)
                mblob = sm.link(staged, mMailbox, mId, getSavedSequence());
            else
                mblob = sm.renameTo(staged, mMailbox, mId, getSavedSequence());
            mMailbox.markOtherItemDirty(mblob);
        }
        mBlob = null;
        mData.locator = mblob == null ? null : mblob.getLocator();

        // rewrite the DB row to reflect our new view (MUST call saveData)
        reanalyze(content, size);

        return mblob;
    }

    @SuppressWarnings("unused")
    int getMaxRevisions() throws ServiceException {
        return 1;
    }

    List<MailItem> loadRevisions() throws ServiceException {
        if (mRevisions == null) {
            mRevisions = new ArrayList<MailItem>();

            if (isTagged(Flag.FlagInfo.VERSIONED)) {
                for (UnderlyingData data : DbMailItem.getRevisionInfo(this, inDumpster()))
                    mRevisions.add(constructItem(mMailbox, data));
            }
        }

        return mRevisions;
    }

    void addRevision(boolean persist) throws ServiceException {
        // don't take two revisions for the same data
        if (mData.modMetadata == mMailbox.getOperationChangeID())
            return;

        Folder folder = getFolder();
        int maxNumRevisions = getMaxRevisions();

        // record the current version as a revision
        if (maxNumRevisions != 1) {
            loadRevisions();

            // Don't take two revisions for the same data.
            if (!mRevisions.isEmpty()) {
                MailItem lastRev = mRevisions.get(mRevisions.size() - 1);
                if (lastRev.mData.modContent == mData.modContent && lastRev.mData.modMetadata == mData.modMetadata)
                    return;

                int maxVer = 0;
                for (MailItem rev : mRevisions)
                    maxVer = Math.max(maxVer, rev.getVersion());

                if (mVersion <= maxVer) {
                    ZimbraLog.mailop.info("Item's current version is not greater than highest revision; " +
                                          "adjusting to " + (maxVer + 1) + " (was " + mVersion + ")");
                    mVersion = maxVer + 1;
                }
            }

            UnderlyingData data = mData.clone();
            data.metadata = encodeMetadata().toString();
            data.setFlag(Flag.FlagInfo.UNCACHED);
            mRevisions.add(constructItem(mMailbox, data));

            mMailbox.updateSize(mData.size, isQuotaCheckRequired());
            folder.updateSize(0, 0, mData.size);

            ZimbraLog.mailop.debug("saving revision %d for %s", mVersion, getMailopContext(this));

            DbMailItem.snapshotRevision(this, mVersion);
            if (!isTagged(Flag.FlagInfo.VERSIONED)) {
                tagChanged(mMailbox.getFlagById(Flag.ID_VERSIONED), true);
            }
        }

        // now that we've made a copy of the item, we can increment the version number
        mVersion++;

        // Purge revisions and their blobs beyond revision count limit.
        if (maxNumRevisions > 0 && isTagged(Flag.FlagInfo.VERSIONED)) {
            List<MailItem> revisions = loadRevisions();
            int numRevsToPurge = revisions.size() - (maxNumRevisions - 1);  // -1 for main item
            if (numRevsToPurge > 0) {
                List<MailItem> toPurge = new ArrayList<MailItem>();
                int numPurged = 0;
                for (Iterator<MailItem> it = revisions.iterator(); it.hasNext() && numPurged < numRevsToPurge; numPurged++) {
                    MailItem revision = it.next();
                    toPurge.add(revision);
                    it.remove();
                }

                // The following logic depends on version, mod_metadata and mod_content each being
                // monotonically increasing in the revisions list. (f(n) <= f(n+1))

                // Filter out blobs that are still in use; mark the rest for deletion.
                int oldestRemainingSavedSequence =
                    revisions.isEmpty() ? mData.modContent : revisions.get(0).getSavedSequence();
                for (MailItem revision : toPurge) {
                    if (revision.getSavedSequence() < oldestRemainingSavedSequence) {
                        mMailbox.updateSize(-revision.getSize());
                        folder.updateSize(0, 0, -revision.getSize());
                        revision.markBlobForDeletion();
                    }
                }
                // Purge revisions from db.
                int highestPurgedVer = toPurge.get(toPurge.size() - 1).getVersion();
                DbMailItem.purgeRevisions(this, highestPurgedVer, true);
            }
            if (revisions.isEmpty()) {
                tagChanged(mMailbox.getFlagById(Flag.ID_VERSIONED), false);
            }
        }

        mData.metadataChanged(mMailbox);
        if (persist) {
            saveData(new DbMailItem(mMailbox));
        }
    }

    // do *not* make this public, as it'd skirt Mailbox-level synchronization and caching
    MailItem getRevision(int version) throws ServiceException {
        if (version == mVersion) {
            return this;
        }
        if (version <= 0 || version > mVersion || !isTagged(Flag.FlagInfo.VERSIONED)) {
            return null;
        }
        for (MailItem revision : loadRevisions()) {
            if (revision.mVersion == version)
                return revision;
        }
        return null;
    }

    void purgeRevision(int version, boolean includeOlderRevisions) throws ServiceException {
        if (!canAccess(ACL.RIGHT_WRITE))
            throw ServiceException.PERM_DENIED("you do not have the necessary permissions on the item");
        DbMailItem.purgeRevisions(this, version, includeOlderRevisions);
        mRevisions = null;
    }

    /** Recalculates the size, metadata, etc. for an existing MailItem and
     *  persists that information to the database.  Maintains any existing
     *  mutable metadata.  Updates mailbox and folder sizes appropriately.
     *
     * @param data  The (optional) extra item data for indexing (e.g.
     *              a Message's {@link com.zimbra.cs.index.ParsedMessage}. */
    void reanalyze(Object data, long newSize) throws ServiceException {
        throw ServiceException.FAILURE("reanalysis of " + getType() + "s not supported", null);
    }

    @SuppressWarnings("unused") void detach() throws ServiceException  { }

    /** Updates the item's unread state.  Persists the change to the
     *  database and cache, and also updates the unread counts for the
     *  item's {@link Folder} and {@link Tag}s appropriately.
     *
     * @param unread  <tt>true</tt> to mark the item unread,
     *                <tt>false</tt> to mark it as read.
     * @perms {@link ACL#RIGHT_WRITE} on the item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.CANNOT_TAG</tt> - if the item can't be marked unread
     *    <li><tt>service.FAILURE</tt> - if there's a database failure
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul> */
    void alterUnread(boolean unread) throws ServiceException {
        // detect NOOPs and bail
        if (unread == isUnread()) {
            return;
        }
        Flag unreadFlag = Flag.FlagInfo.UNREAD.toFlag(mMailbox);
        if (!unreadFlag.canTag(this)) {
            throw MailServiceException.CANNOT_TAG(unreadFlag, this);
        } else if (!canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        }

        markItemModified(Change.UNREAD);
        int delta = unread ? 1 : -1;
        mData.metadataChanged(mMailbox);
        updateUnread(delta, isTagged(Flag.FlagInfo.DELETED) ? delta : 0);
        DbMailItem.alterUnread(getMailbox(), ImmutableList.of(getId()), unread);
    }

    /** Tags or untags an item.  Persists the change to the database and
     *  cache.  If the item is unread and its tagged state is changing,
     *  updates the {@link Tag}'s unread count appropriately.  Note that the
     *  parent is not fetched from the database, so notifications may be off
     *  in the case of uncached {@link Conversation}s when a {@link Message}
     *  changes state.<p>
     *
     *  You must use {@link #alterUnread} to change an item's unread state.
     *
     * @param tag  The tag or flag to add or remove from the item.
     * @param add  <tt>true</tt> to tag the item, <tt>false</tt> to untag it.
     * @perms {@link ACL#RIGHT_WRITE} on the item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.CANNOT_TAG</tt> - if the item can't be tagged with the
     *        specified tag
     *    <li><tt>service.FAILURE</tt> - if there's a database failure or if
     *        an invalid Tag is supplied
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul>
     * @see #alterUnread(boolean) */
    void alterTag(Tag tag, boolean add) throws ServiceException {
        if (tag == null) {
            throw ServiceException.FAILURE("no tag supplied when trying to tag item " + mId, null);
        } else if (!isTaggable() || (add && !tag.canTag(this))) {
            throw MailServiceException.CANNOT_TAG(tag, this);
        } else if (tag.getId() == Flag.ID_UNREAD) {
            throw ServiceException.FAILURE("unread state must be set with alterUnread", null);
        } else if (!canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        }
        // detect NOOPs and bail
        if (add == isTagged(tag)) {
            return;
        }
        // don't let the user tag things as "has attachments" or "draft"
        if (tag instanceof Flag && ((Flag) tag).isSystemFlag()) {
            throw MailServiceException.CANNOT_TAG(tag, this);
        }
        // grab the parent *before* we make any other changes
        MailItem parent = getParent();

        // change our cached tags
        tagChanged(tag, add);

        // since we're adding/removing a tag, the tag's unread count may change
        int unreadDelta = (add ? 1 : -1) * mData.unreadCount;
        if (tag.trackUnread() && unreadDelta != 0) {
            tag.updateUnread(unreadDelta, isTagged(Flag.FlagInfo.DELETED) ? unreadDelta : 0);
        }

        int countDelta = (add ? 1 : -1) * (isLeafNode() ? 1 : (int) mData.size);
        tag.updateSize(countDelta, isTagged(Flag.FlagInfo.DELETED) ? countDelta : 0);

        // if we're adding/removing the \Deleted flag, update the folder and tag "deleted" and "deleted unread" counts
        if (tag.getId() == Flag.ID_DELETED) {
            getFolder().updateSize(0, add ? 1 : -1, 0);
            updateTagSizes(0, add ? 1 : -1, 0);
            // note that Message.updateUnread() calls updateTagUnread()
            if (unreadDelta != 0) {
                updateUnread(0, unreadDelta);
            }
        }

        if (ZimbraLog.mailop.isDebugEnabled()) {
            ZimbraLog.mailop.debug("Setting %s for %s.", getMailopContext(tag), getMailopContext(this));
        }
        // alter our tags in the DB
//        DbTag.alterTag(this, tag, add);
        DbTag.alterTag(tag, Arrays.asList(getId()), add);

        // tell our parent about the tag change (note: must happen after DbMailItem.alterTag)
        if (parent != null) {
            parent.inheritedTagChanged(tag, add);
        }
    }

    final void alterSystemFlag(Flag flag, boolean newValue) throws ServiceException {
        if (flag == null) {
            throw ServiceException.FAILURE("no tag supplied when trying to tag item " + mId, null);
        } else if ((flag.toBitmask() & Flag.FLAGS_SYSTEM) == 0) {
            throw ServiceException.FAILURE("requested to alter a non-system tag", null);
        } else if (newValue && !flag.canTag(this)) {
            throw MailServiceException.CANNOT_TAG(flag, this);
        } else if (newValue == isTagged(flag)) {
            return;
        }

        // grab the parent *before* we make any other changes
        MailItem parent = getParent();

        // change our cached tags
        tagChanged(flag, newValue);

        // alter our tags in the DB
        DbTag.alterTag(flag, Arrays.asList(getId()), newValue);

        // tell our parent about the tag change (note: must happen after DbMailItem.alterTag)
        if (parent != null) {
            parent.inheritedTagChanged(flag, newValue);
        }
    }

    /** Updates the object's in-memory state to reflect a {@link Tag} change.
     *  Does not update the database.
     *
     * @param tag  The tag that was added or rmeoved from this object.
     * @param add  <tt>true</tt> if the item was tagged,
     *             <tt>false</tt> if the item was untagged. */
    protected void tagChanged(Tag tag, boolean add) throws ServiceException {
        boolean isFlag = tag instanceof Flag;
        markItemModified(isFlag ? Change.FLAGS : Change.TAGS);
        // changing a system flag is not a syncable event
        if (!isFlag || !((Flag) tag).isSystemFlag()) {
            mData.metadataChanged(mMailbox);
        }

        if (isFlag) {
            if (add) {
                mData.setFlag((Flag) tag);
            } else {
                mData.unsetFlag((Flag) tag);
            }
        } else {
            Set<String> tags = Sets.newLinkedHashSet();
            Collections.addAll(tags, mData.getTags());
            if (add) {
                tags.add(tag.getName());
            } else {
                tags.remove(tag.getName());
            }
            mData.setTags(tags.isEmpty() ? null : new Tag.NormalizedTags(tags));
        }
    }

    @SuppressWarnings("unused")
    protected void inheritedTagChanged(Tag tag, boolean add) throws ServiceException  { }

    /** Updates the in-memory unread count for the item.  The base-class
     *  implementation does not cascade the change to the item's parent,
     *  folder, and tags, as {@link Message#updateUnread(int,int)} does.
     *
     * @param delta  The change in unread count for this item. */
    protected void updateUnread(int delta, int deletedDelta) throws ServiceException {
        if (delta == 0 || !trackUnread()) {
            return;
        }
        // update our unread count (should we check that we don't have too many unread?)
        markItemModified(Change.UNREAD);
        mData.unreadCount += delta;
        if (mData.unreadCount < 0) {
            throw ServiceException.FAILURE("inconsistent state: unread < 0 for item " + mId, null);
        }
    }

    /** Adds <tt>delta</tt> to the unread count of each {@link Tag}
     *  assigned to this {@code MailItem}.
     *
     * @param delta  The (signed) change in number unread.
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.NO_SUCH_FOLDER</tt> - if there's an error fetching the
     *        item's {@link Folder}</ul> */
    protected void updateTagUnread(int delta, int deletedDelta) throws ServiceException {
        if ((delta == 0 && deletedDelta == 0) || !isTaggable())
            return;

        String[] tags = mData.getTags();
        for (String name : tags) {
            try {
                mMailbox.getTagByName(name).updateUnread(delta, deletedDelta);
            } catch (MailServiceException.NoSuchItemException nsie) {
                ZimbraLog.mailbox.warn("item %d has nonexistent tag %s", mId, name);
                continue;
            }
        }
    }

    /** Adds <tt>delta</tt> to the unread count of each {@link Tag}
     *  assigned to this {@code MailItem}.
     *
     * @param delta  The (signed) change in number unread.
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.NO_SUCH_FOLDER</tt> - if there's an error fetching the
     *        item's {@link Folder}</ul> */
    protected void updateTagSizes(int countDelta, int deletedDelta, long sizeDelta) throws ServiceException {
        if ((countDelta == 0 && deletedDelta == 0 && sizeDelta == 0) || !isTaggable())
            return;

        String[] tags = mData.getTags();
        for (String name : tags) {
            try {
                mMailbox.getTagByName(name).updateSize(countDelta, deletedDelta);
            } catch (MailServiceException.NoSuchItemException nsie) {
                ZimbraLog.mailbox.warn("item %d has nonexistent tag %s", mId, name);
                continue;
            }
        }
    }

    /** Updates the user-settable set of {@link Flag}s and {@link Tag}s on
     *  the item.  This overwrites the old set of flags and tags, but will
     *  not change system flags that are normally immutable after item
     *  creation, like {@link Flag#BITMASK_ATTACHED} and {@link Flag#BITMASK_DRAFT}.
     *  If a specified flag or tag does not exist, it is ignored.
     *
     * @param flags  The bitmask of user-settable flags to apply.
     * @param ntags  The set of tag names to apply.
     * @perms {@link ACL#RIGHT_WRITE} on the item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>service.FAILURE</tt> - if there's a database failure
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul> */
    void setTags(int flags, Tag.NormalizedTags ntags) throws ServiceException {
        if (!canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        }

        // FIXME: more optimal would be to do this with a single db UPDATE...

        // make sure the caller can't change immutable flags
        flags = (flags & ~Flag.FLAGS_SYSTEM) | (getFlagBitmask() & Flag.FLAGS_SYSTEM);
        // handle flags first...
        if (flags != mData.getFlags()) {
            markItemModified(Change.FLAGS);
            for (int flagId : Flag.toId(flags ^ mData.getFlags())) {
                Flag flag = Flag.of(mMailbox, flagId);
                if (flag != null) {
                    alterTag(flag, !isTagged(flag));
                }
            }
        }

        // then handle tags...
        if (ntags.getTags() != mData.getTags()) {
            Set<String> removed = Sets.newHashSet(mData.getTags()), added = Sets.newHashSet(ntags.getTags());
            removed.removeAll(added);
            added.removeAll(Arrays.asList(mData.getTags()));

            for (String tagName : removed) {
                try {
                    alterTag(mMailbox.getTagByName(tagName), false);
                } catch (MailServiceException.NoSuchItemException nsie) { }
            }
            for (String tagName : added) {
                try {
                    alterTag(mMailbox.getTagByName(tagName), true);
                } catch (MailServiceException.NoSuchItemException nsie) { }
            }
        }
    }

    /** Copies an item to a {@link Folder}.  Persists the new item to the
     *  database and the in-memory cache.  Copies to the same folder as the
     *  original item will succeed.<p>
     *
     *  Immutable copied items (both the original and the target) share the
     *  same entry in the index and get the {@link Flag#BITMASK_COPIED} flag to
     *  facilitate garbage collection of index entries.  (Mutable copied items
     *  are indexed separately.)  They do not share the same blob on disk,
     *  although the system will use a hard link where possible.  Copying a
     *  {@link Message} will put it in the same {@link Conversation} as the
     *  original (exceptions: draft messages, messages in the Junk folder).
     *
     * @param folder    The folder to copy the item to.
     * @param copyId    The item id for the newly-created copy.
     * @param parent    The target parent MailItem for the new copy.
     * @perms {@link ACL#RIGHT_INSERT} on the target folder,
     *        {@link ACL#RIGHT_READ} on the original item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.CANNOT_COPY</tt> - if the item is not copyable
     *    <li><tt>mail.CANNOT_CONTAIN</tt> - if the target folder can't hold
     *        the copy of the item
     *    <li><tt>service.FAILURE</tt> - if there's a database failure
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul> */
    MailItem copy(Folder folder, int copyId, MailItem parent) throws IOException, ServiceException {
        if (!isCopyable())
            throw MailServiceException.CANNOT_COPY(mId);
        if (!folder.canContain(this))
            throw MailServiceException.CANNOT_CONTAIN();

        if (!canAccess(ACL.RIGHT_READ))
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        if (!folder.canAccess(ACL.RIGHT_INSERT))
            throw ServiceException.PERM_DENIED("you do not have the required rights on the target folder");

        // We'll share the index entry if this item can't change out from under us. Re-index the copy if existing item
        // (a) wasn't indexed or (b) is mutable or (c) existing item is in dumpster (which implies copy is not in
        // dumpster)
        boolean inDumpster = inDumpster();
        boolean shareIndex = !inDumpster && !isMutable() && getIndexStatus() == IndexStatus.DONE && !folder.inSpam();

        // if the copy or original is in Spam, put the copy in its own conversation
        boolean detach = parent == null || isTagged(Flag.FlagInfo.DRAFT) || inSpam() != folder.inSpam();
        parent = detach ? null : parent;

        if (shareIndex && !isTagged(Flag.FlagInfo.COPIED)) {
            alterSystemFlag(mMailbox.getFlagById(Flag.ID_COPIED), true);
            if (ZimbraLog.mailop.isDebugEnabled()) {
                ZimbraLog.mailop.debug("setting copied flag for %s", getMailopContext(this));
            }
        }
        StoreManager sm = StoreManager.getInstance();

        // main item
        String locator = null;
        MailboxBlob srcMblob = getBlob();
        if (srcMblob != null) {
            MailboxBlob mblob = sm.link(srcMblob, mMailbox, copyId, mMailbox.getOperationChangeID());
            mMailbox.markOtherItemDirty(mblob);
            locator = mblob.getLocator();
        }

        UnderlyingData data = mData.duplicate(copyId, folder.getId(), locator);
        data.parentId = detach ? -1 : parent.mId;
        data.indexId = shareIndex ? getIndexId() : IndexStatus.DEFERRED.id();
        if (!shareIndex) {
            data.unsetFlag(Flag.FlagInfo.COPIED);
        }
        data.metadata = encodeMetadata().toString();
        data.contentChanged(mMailbox);

        ZimbraLog.mailop.info("Copying %s: copyId=%d, folderId=%d, folderName=%s, parentId=%d.",
                              getMailopContext(this), copyId, folder.getId(), folder.getName(), data.parentId);
        DbMailItem.copy(this, copyId, folder, data.indexId, data.parentId, data.locator, data.metadata, inDumpster);
        if (this instanceof CalendarItem)
            DbMailItem.copyCalendarItem((CalendarItem) this, copyId, inDumpster);

        // older revisions
        // Copy revisions only when recovering from dumpster.  When copying from one non-dumpster folder to another,
        // it is never desirable to copy old revisions. (bug 55070)
        if (inDumpster) {
            for (MailItem revision : loadRevisions()) {
                MailboxBlob srcRevBlob = revision.getBlob();
                String revLocator = null;
                if (srcRevBlob != null) {
                    MailboxBlob copyRevBlob = sm.link(srcRevBlob, mMailbox, copyId, revision.getSavedSequence());
                    mMailbox.markOtherItemDirty(copyRevBlob);
                    revLocator = copyRevBlob.getLocator();
                }
                DbMailItem.copyRevision(revision, copyId, revLocator, inDumpster);
            }
        }

        MailItem copy = constructItem(mMailbox, data);
        copy.finishCreation(parent);

        if (!shareIndex) {
            mMailbox.index.add(copy);
        }

        return copy;
    }

    /** Copies the item to the target folder.  Persists the new item to the
     *  database and the in-memory cache.  Copies to the same folder as the
     *  original item will succeed, but it is strongly suggested that
     *  {@link #copy(Folder, int, int, short)} be used in that case.<p>
     *
     *  Immutable copied items (both the original and the target) share the
     *  same entry in the index and get the {@link Flag#BITMASK_COPIED} flag to
     *  facilitate garbage collection of index entries.  (Mutable copied items
     *  are indexed separately.)  They do not share the same blob on disk,
     *  although the system will use a hard link where possible.  Copied
     *  {@link Message}s are remain in the same {@link Conversation}, but the
     *  <b>original</b> Message is placed in a new {@link VirtualConversation}
     *  rather than being grouped with the copied Message.
     *
     * @param target  The folder to copy the item to.
     * @param copyId  The item id for the newly-created copy.
     * @perms {@link ACL#RIGHT_INSERT} on the target folder,
     *        {@link ACL#RIGHT_READ} on the original item
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.CANNOT_COPY</tt> - if the item is not copyable
     *    <li><tt>mail.CANNOT_CONTAIN</tt> - if the target folder can't hold
     *        the copy of the item
     *    <li><tt>service.FAILURE</tt> - if there's a database failure
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul> */
    MailItem icopy(Folder target, int copyId) throws IOException, ServiceException {
        if (!isCopyable())
            throw MailServiceException.CANNOT_COPY(mId);
        if (!target.canContain(this))
            throw MailServiceException.CANNOT_CONTAIN();

        // permissions required are the same as for copy()
        if (!canAccess(ACL.RIGHT_READ))
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        if (!target.canAccess(ACL.RIGHT_INSERT))
            throw ServiceException.PERM_DENIED("you do not have the required rights on the target folder");

        // fetch the parent *before* changing the DB
        MailItem parent = getParent();

        // first, copy the item to the target folder while setting:
        //   - FLAGS -> FLAGS | Flag.BITMASK_COPIED
        //   - INDEX_ID -> old index id
        //   - FOLDER_ID -> new folder
        //   - IMAP_ID -> new IMAP uid
        //   - VOLUME_ID -> target volume ID
        // then, update the original item
        //   - PARENT_ID -> NULL
        //   - FLAGS -> FLAGS | Flag.BITMASK_COPIED
        // finally, update OPEN_CONVERSATION if PARENT_ID was NULL
        //   - ITEM_ID = copy's id for hash

        String locator = null;
        MailboxBlob srcMblob = getBlob();
        if (srcMblob != null) {
            StoreManager sm = StoreManager.getInstance();
            MailboxBlob mblob = sm.link(srcMblob, mMailbox, copyId, mMailbox.getOperationChangeID());
            mMailbox.markOtherItemDirty(mblob);
            locator = mblob.getLocator();
        }

        // We'll share the index entry if this item can't change out from under us. Re-index the copy if existing item
        // (a) wasn't indexed or (b) is mutable.
        boolean shareIndex = !isMutable() && getIndexStatus() == IndexStatus.DONE && !target.inSpam();

        UnderlyingData data = mData.duplicate(copyId, target.getId(), locator);
        data.metadata = encodeMetadata().toString();
        data.imapId = copyId;
        data.indexId = shareIndex ? getIndexId() : IndexStatus.DEFERRED.id();
        data.contentChanged(mMailbox);

        ZimbraLog.mailop.info("Performing IMAP copy of %s: copyId=%d, folderId=%d, folderName=%s, parentId=%d.",
            getMailopContext(this), copyId, target.getId(), target.getName(), data.parentId);
        DbMailItem.icopy(this, data, shareIndex);

        MailItem copy = constructItem(mMailbox, data);
        copy.finishCreation(null);

        if (shareIndex && !isTagged(Flag.FlagInfo.COPIED)) {
            Flag copiedFlag = mMailbox.getFlagById(Flag.ID_COPIED);
            tagChanged(copiedFlag, true);
            copy.tagChanged(copiedFlag, true);
            if (parent != null)
                parent.inheritedTagChanged(copiedFlag, true);
        }

        if (parent != null && parent.getId() > 0) {
            markItemModified(Change.PARENT);
            parent.markItemModified(Change.CHILDREN);
            mData.metadataChanged(mMailbox);
            mData.parentId = mData.type == Type.MESSAGE.toByte() ? -mId : -1;
        }

        if (!shareIndex) {
            mMailbox.index.add(copy);
        }

        return copy;
    }

    /** The regexp defining printable characters not permitted in item
     *  names.  These are: ':', '/', '"', '\t', '\r', and '\n'. */
    private static final String INVALID_NAME_CHARACTERS = "[:/\"\t\r\n]";

    private static final String INVALID_NAME_PATTERN = ".*" + INVALID_NAME_CHARACTERS + ".*";

    /** The maximum length for an item name.  This is not the maximum length
     *  of a <u>path</u>, just the maximum length of a single item or folder's
     *  name. */
    public static final int MAX_NAME_LENGTH = 128;

    /** Validates a proposed item name.  Names must be less than
     *  {@link #MAX_NAME_LENGTH} characters long, must contain non-whitespace
     *  characters, and may not contain any characters banned in XML or
     *  contained in {@link #INVALID_NAME_CHARACTERS} (':', '/', '"', '\t',
     *  '\r', '\n').
     *
     * @param name  The proposed item name.
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.INVALID_NAME</tt> - if the name is not acceptable</ul>
     * @return the passed-in name with trailing whitespace stripped.
     * @see StringUtil#stripControlCharacters(String) */
    static String validateItemName(String name) throws ServiceException {
        // reject invalid characters in the name
        if (name == null || name != StringUtil.stripControlCharacters(name) || name.matches(INVALID_NAME_PATTERN)) {
            throw MailServiceException.INVALID_NAME(name);
        }
        // strip trailing whitespace and validate length of resulting name
        String trimmed = StringUtil.trimTrailingSpaces(name);
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw MailServiceException.INVALID_NAME(name);
        }
        return trimmed;
    }

    public static String normalizeItemName(String name) {
        try {
            return validateItemName(name);
        } catch (ServiceException e) {
            String normalized = StringUtil.stripControlCharacters(name);
            if (normalized == null) {
                normalized = "";
            }
            if (normalized.length() > MailItem.MAX_NAME_LENGTH) {
                normalized = normalized.substring(0, MailItem.MAX_NAME_LENGTH);
            }
            if (normalized.matches(INVALID_NAME_PATTERN)) {
                normalized = normalized.replaceAll(INVALID_NAME_CHARACTERS, "");
            }
            normalized = StringUtil.trimTrailingSpaces(normalized);
            if (normalized.trim().equals("")) {
                normalized = "item" + System.currentTimeMillis();
            }
            return normalized;
        }
    }

    /** Renames the item in place.  Altering an item's name's case (e.g.
     *  from <tt>foo</tt> to <tt>FOO</tt>) is allowed.
     *
     * @param name  The new name for this item.
     * @perms {@link ACL#RIGHT_WRITE} on the item
     * @throws ServiceException   The following error codes are possible:<ul>
     *    <li><tt>mail.IMMUTABLE_OBJECT</tt> - if the item can't be renamed
     *    <li><tt>mail.ALREADY_EXISTS</tt> - if a different item by that name
     *        already exists in the current folder
     *    <li><tt>mail.INVALID_NAME</tt> - if the new item's name is invalid
     *    <li><tt>service.FAILURE</tt> - if there's a database failure
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul>
     * @see #validateItemName(String) */
    void rename(String name) throws ServiceException {
        rename(name, getFolder());
    }

    /** Renames the item and optionally moves it.  Altering an item's case
     *  (e.g. from <tt>foo</tt> to <tt>FOO</tt>) is allowed.  If you don't
     *  want the item to be moved, you must pass <tt>folder.getFolder()</tt>
     *  as the second parameter.
     *
     * @param newName  The new name for this item.
     * @param target   The new parent folder to move this item to.
     * @perms {@link ACL#RIGHT_WRITE} on the item to rename it,
     *        {@link ACL#RIGHT_DELETE} on the parent folder and
     *        {@link ACL#RIGHT_INSERT} on the target folder to move it
     * @throws ServiceException   The following error codes are possible:<ul>
     *    <li><tt>mail.IMMUTABLE_OBJECT</tt> - if the item can't be renamed
     *    <li><tt>mail.ALREADY_EXISTS</tt> - if a different item by that name
     *        already exists in the target folder
     *    <li><tt>mail.INVALID_NAME</tt> - if the new item's name is invalid
     *    <li><tt>service.FAILURE</tt> - if there's a database failure
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul>
     * @see #validateItemName(String)
     * @see #move(Folder) */
    void rename(String newName, Folder target) throws ServiceException {
        String name = validateItemName(newName);

        boolean renamed = !name.equals(mData.name);
        boolean moved   = target != getFolder();

        if (!renamed && !moved)
            return;

        if (moved && target.getId() != Mailbox.ID_FOLDER_TRASH && target.getId() != Mailbox.ID_FOLDER_SPAM && !target.canAccess(ACL.RIGHT_INSERT)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the target item");
        } else if (moved && !canAccess(ACL.RIGHT_DELETE)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        } else if (renamed && !canAccess(ACL.RIGHT_WRITE)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        }

        if (renamed) {
            if (mData.name == null) {
                throw MailServiceException.CANNOT_RENAME(getType());
            } else if (!isMutable()) {
                throw MailServiceException.IMMUTABLE_OBJECT(mId);
            }

            try {
                MailItem conflict = mMailbox.getItemByPath(null, name, target.getId());
                if (conflict != null && conflict != this) {
                    throw MailServiceException.ALREADY_EXISTS(name);
                }
            } catch (MailServiceException.NoSuchItemException nsie) { }

            if (ZimbraLog.mailop.isDebugEnabled()) {
                ZimbraLog.mailop.debug("renaming " + getMailopContext(this) + " to " + name);
            }

            // XXX: note that we don't update mData.folderId here, as we need the subsequent
            //   move() to execute (it does several things that this code does not)
            markItemModified(Change.NAME);
            mData.name = name;
            mData.setSubject(name);
            mData.dateChanged = mMailbox.getOperationTimestamp();
            mData.metadataChanged(mMailbox);

            saveName(target.getId());
        }

        if (moved) {
            move(target);
        }
    }

    /** Moves an item to a different {@link Folder}.  Persists the change
     *  to the database and the in-memory cache.  Updates all relevant
     *  unread counts, folder sizes, etc.<p>
     *
     *  Items moved to the Trash folder are automatically marked read.
     *  {@link Message}s moved to the Junk folder are removed from their
     *  {@link Conversation} (if any).  Conversations moved to the Junk
     *  folder will not receive newly-delivered messages.
     *
     * @param target  The folder to move the item to.
     * @perms {@link ACL#RIGHT_INSERT} on the target folder,
     *        {@link ACL#RIGHT_DELETE} on the source folder
     * @throws ServiceException  The following error codes are possible:<ul>
     *    <li><tt>mail.IMMUTABLE_OBJECT</tt> - if the item is not movable
     *    <li><tt>mail.CANNOT_CONTAIN</tt> - if the target folder can't
     *        hold the item
     *    <li><tt>service.FAILURE</tt> - if there's a database failure
     *    <li><tt>service.PERM_DENIED</tt> - if you don't have sufficient
     *        permissions</ul>
     * @return whether anything was actually moved */
    boolean move(Folder target) throws ServiceException {
        if (mData.folderId == target.getId()) {
            return false;
        }
        markItemModified(Change.FOLDER);
        if (!isMovable()) {
            throw MailServiceException.IMMUTABLE_OBJECT(mId);
        }
        if (!target.canContain(this)) {
            throw MailServiceException.CANNOT_CONTAIN();
        }
        Folder oldFolder = getFolder();
        if (!oldFolder.canAccess(ACL.RIGHT_DELETE)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the source folder");
        }
        if (target.getId() != Mailbox.ID_FOLDER_TRASH && target.getId() != Mailbox.ID_FOLDER_SPAM &&
                !target.canAccess(ACL.RIGHT_INSERT)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the target folder");
        }
        if (isLeafNode()) {
            boolean isDeleted = isTagged(Flag.FlagInfo.DELETED);
            oldFolder.updateSize(-1, isDeleted ? -1 : 0, -getTotalSize());
            target.updateSize(1, isDeleted ? 1 : 0, getTotalSize());
        }

        if (!inTrash() && target.inTrash()) {
            // moving something to Trash also marks it as read
            if (mData.unreadCount > 0) {
                alterUnread(false);
            }
        } else {
            boolean isDeleted = isTagged(Flag.FlagInfo.DELETED);
            oldFolder.updateUnread(-mData.unreadCount, isDeleted ? -mData.unreadCount : 0);
            target.updateUnread(mData.unreadCount, isDeleted? mData.unreadCount : 0);
        }
        // moving a message (etc.) to Spam removes it from its conversation
        if (!inSpam() && target.inSpam()) {
            detach();
        }
        // item moved out of spam, so update the index id (will be written to DB in DbMailItem.setFolder());
        if (inSpam() && !target.inSpam() && getIndexStatus() == IndexStatus.DONE) {
            mMailbox.index.add(this);
        }

        ZimbraLog.mailop.info("moving " + getMailopContext(this) + " to " + getMailopContext(target));
        DbMailItem.setFolder(this, target);
        folderChanged(target, 0);
        return true;
    }

    /** Records all relevant changes to the in-memory object for when an item
     *  gets moved to a new {@link Folder}.  Does <u>not</u> persist those
     *  changes to the database.
     *
     * @param newFolder  The folder the item is being moved to.
     * @param imapId     The new IMAP ID for the item after the operation.
     * @throws ServiceException if we're not in a transaction */
    void folderChanged(Folder newFolder, int imapId) throws ServiceException {
        if (mData.folderId == newFolder.getId()) {
            return;
        }
        markItemModified(Change.FOLDER);
        mData.metadataChanged(mMailbox);
        mData.folderId = newFolder.getId();
        mData.imapId   = mMailbox.isTrackingImap() ? imapId : mData.imapId;
    }

    void addChild(MailItem child) throws ServiceException {
        markItemModified(Change.CHILDREN);
        if (!canParent(child)) {
            throw MailServiceException.CANNOT_PARENT();
        }
        if (mMailbox != child.getMailbox()) {
            throw MailServiceException.WRONG_MAILBOX();
        }
    }

    /**
     * @throws ServiceException subclass may throw
     */
    void removeChild(MailItem child) throws ServiceException {
        markItemModified(Change.CHILDREN);

        // remove parent reference from the child
        if (child.mData.parentId == mId) {
            child.mData.parentId = -1;
        }
    }

    /** A record of all the relevant data about a set of items that we're
     *  in the process of deleting via a call to {@link MailItem#delete}. */
    public static class PendingDelete {
        /** The id of the item that {@link MailItem#delete} was called on. */
        public int rootId;

        /** a bitmask of all the types of MailItems that are being deleted.
         * see {@link MailItem#typeToBitmask} */
        public int deletedTypes;

        /** Whether some of the item's children are not being deleted. */
        public boolean incomplete;

        /** The total size of all the items being deleted. */
        public long size;

        /** The number of {@link Contact}s being deleted. */
        public int contacts;

        /** The ids of all items being deleted. */
        public TypedIdList itemIds = new TypedIdList();

        /** The ids of all unread items being deleted.  This is a subset of
         *  {@link #itemIds}. */
        public List<Integer> unreadIds = new ArrayList<Integer>(1);

        /** The ids of all items that must be deleted but whose deletion
         *  must be deferred because of foreign key constraints. (E.g.
         *  {@link Conversation}s whose messages are all deleted during a
         *  {@link Folder} delete.) */
        public List<Integer> cascadeIds;

        /** The ids of all items that have been <u>modified</u> but not deleted
         *  during the delete.  (E.g. {@link Conversation}s whose messages are
         *  <b>not</b> all deleted during a {@link Folder} delete.)  */
        public Set<Integer> modifiedIds = new HashSet<Integer>(2);

        /** The document ids that need to be removed from the index. */
        public final List<Integer> indexIds = new ArrayList<Integer>(1);

        /** The ids of all items with the {@link Flag#BITMASK_COPIED} flag being
         *  deleted.  Items in <tt>sharedIndex</tt> whose last copies are
         *  being removed are added to {@link #indexIds} via a call to
         *  {@link DbMailItem#resolveSharedIndex}. */
        public Set<Integer> sharedIndex;

        /** The {@link com.zimbra.cs.store.Blob}s for all items being deleted that have content
         *  persisted in the store. */
        public List<MailboxBlob> blobs = new ArrayList<MailboxBlob>(1);

        /** Maps {@link Folder} ids to {@link DbMailItem.LocationCount}s
         *  tracking various per-folder counts for items being deleted. */
        public Map<Integer, DbMailItem.LocationCount> folderCounts = Maps.newHashMapWithExpectedSize(1);

        /** Maps {@link Tag} names to {@link DbMailItem.LocationCount}s
         *  tracking various per-tag counts for items being deleted. */
        public Map<String, DbMailItem.LocationCount> tagCounts = Maps.newHashMapWithExpectedSize(1);

        /** Digests of all blobs being deleted. */
        public Set<String> blobDigests = new HashSet<String>(2);

        /** Combines the data from another <tt>PendingDelete</tt> into
         *  this object.  The other <tt>PendingDelete</tt> is unmodified.
         *
         * @return this item */
        PendingDelete add(PendingDelete other) {
            if (other != null) {
                deletedTypes |= other.deletedTypes;
                incomplete   |= other.incomplete;

                size     += other.size;
                contacts += other.contacts;

                itemIds.add(other.itemIds);
                unreadIds.addAll(other.unreadIds);
                modifiedIds.addAll(other.modifiedIds);
                indexIds.addAll(other.indexIds);
                blobs.addAll(other.blobs);
                blobDigests.addAll(other.blobDigests);

                if (other.cascadeIds != null) {
                    (cascadeIds == null ? cascadeIds = new ArrayList<Integer>(other.cascadeIds.size()) : cascadeIds).addAll(other.cascadeIds);
                }
                if (other.sharedIndex != null) {
                    (sharedIndex == null ? sharedIndex = new HashSet<Integer>(other.sharedIndex.size()) : sharedIndex).addAll(other.sharedIndex);
                }

                for (Map.Entry<Integer, DbMailItem.LocationCount> entry : other.folderCounts.entrySet()) {
                    DbMailItem.LocationCount lcount = folderCounts.get(entry.getKey());
                    if (lcount == null) {
                        folderCounts.put(entry.getKey(), new DbMailItem.LocationCount(entry.getValue()));
                    } else {
                        lcount.increment(entry.getValue());
                    }
                }
                for (Map.Entry<String, DbMailItem.LocationCount> entry : other.tagCounts.entrySet()) {
                    DbMailItem.LocationCount lcount = tagCounts.get(entry.getKey());
                    if (lcount == null) {
                        tagCounts.put(entry.getKey(), new DbMailItem.LocationCount(entry.getValue()));
                    } else {
                        lcount.increment(entry.getValue());
                    }
                }
            }
            return this;
        }
    }

    enum DeleteScope { ENTIRE_ITEM, CONTENTS_ONLY };

    void delete() throws ServiceException {
        delete(DeleteScope.ENTIRE_ITEM, true);
    }

    void delete(DeleteScope scope, boolean writeTombstones) throws ServiceException {
        if (scope == DeleteScope.ENTIRE_ITEM && !isDeletable()) {
            throw MailServiceException.IMMUTABLE_OBJECT(mId);
        }

        // get the full list of things that are being removed
        PendingDelete info = getDeletionInfo();
        assert(info != null && info.itemIds != null);
        if (scope == DeleteScope.CONTENTS_ONLY || info.incomplete) {
            // make sure to take the container's ID out of the list of deleted items
            info.itemIds.remove(getType(), mId);
        }

        delete(mMailbox, info, this, scope, writeTombstones, inDumpster());
    }

    static void delete(Mailbox mbox, PendingDelete info, MailItem item, DeleteScope scope, boolean writeTombstones) throws ServiceException {
        delete(mbox, info, item, scope, writeTombstones, false);
    }

    static void delete(Mailbox mbox, PendingDelete info, MailItem item, DeleteScope scope, boolean writeTombstones, boolean fromDumpster)
    throws ServiceException {
        // short-circuit now if nothing's actually being deleted
        if (info.itemIds.isEmpty())
            return;

        mbox.markItemDeleted(info.itemIds);

        MailItem parent = null;
        // when applicable, record the deleted MailItem (rather than just its id)
        if (item != null && scope == DeleteScope.ENTIRE_ITEM && !info.incomplete) {
            item.markItemDeleted();
            parent = item.getParent();
        }

        if (!fromDumpster) {
            // update the mailbox's size
            mbox.updateSize(-info.size);
            mbox.updateContactCount(-info.contacts);

            // update conversations and unread counts on folders and tags
            if (item != null) {
                item.propagateDeletion(info);
            } else {
                // update message counts
                List<UnderlyingData> unreadData = DbMailItem.getById(mbox, info.unreadIds, Type.MESSAGE);
                for (UnderlyingData data : unreadData) {
                    MailItem unread = mbox.getItem(data.setFlag(Flag.FlagInfo.UNCACHED));
                    unread.updateUnread(-data.unreadCount, unread.isTagged(Flag.FlagInfo.DELETED) ? -data.unreadCount : 0);
                }

                for (Map.Entry<Integer, DbMailItem.LocationCount> entry : info.folderCounts.entrySet()) {
                    int folderID = entry.getKey();
                    DbMailItem.LocationCount lcount = entry.getValue();
                    mbox.getFolderById(folderID).updateSize(-lcount.count, -lcount.deleted, -lcount.size);
                }
                for (Map.Entry<String, DbMailItem.LocationCount> entry : info.tagCounts.entrySet()) {
                    String tag = entry.getKey();
                    DbMailItem.LocationCount lcount = entry.getValue();
                    mbox.getTagByName(tag).updateSize(-lcount.count, -lcount.deleted);
                }
            }
        }

        // Log mailop statements if necessary
        if (ZimbraLog.mailop.isInfoEnabled()) {
            if (item != null) {
                if (item instanceof VirtualConversation) {
                    ZimbraLog.mailop.info("Deleting Message (id=%d).", ((VirtualConversation) item).getMessageId());
                } else {
                    ZimbraLog.mailop.info("Deleting %s%s.", scope == DeleteScope.CONTENTS_ONLY ? "contents of " : "", getMailopContext(item));
                }
            }

            // If there are any related items being deleted, log them in blocks of 200.
            int itemId = item == null ? 0 : Math.abs(item.getId()); // Use abs() for VirtualConversations
            Set<Integer> idSet = new TreeSet<Integer>();
            for (int id : info.itemIds.getAll()) {
                id = Math.abs(id); // Use abs() for VirtualConversations
                if (id != itemId) {
                    idSet.add(id);
                }
                if (idSet.size() >= 200) {
                    // More than 200 items.
                    ZimbraLog.mailop.info("Deleting items: %s.", StringUtil.join(",", idSet));
                    idSet.clear();
                }
            }
            if (idSet.size() > 0) {
                // Less than 200 items or remainder.
                ZimbraLog.mailop.info("Deleting items: %s.", StringUtil.join(",", idSet));
            }
        }

        // actually delete the items from the DB
        if (info.incomplete || item == null) {
            DbMailItem.delete(mbox, info.itemIds.getAll(), fromDumpster);
        } else if (scope == DeleteScope.CONTENTS_ONLY) {
            DbMailItem.deleteContents(item, fromDumpster);
        } else {
            DbMailItem.delete(item, fromDumpster);
        }

        // remove the deleted item(s) from the mailbox's cache
        if (item != null) {
            item.purgeCache(info, !info.incomplete && scope == DeleteScope.ENTIRE_ITEM);
            if (parent != null) {
                parent.removeChild(item);
            }
        } else if (!info.itemIds.isEmpty()) {
            // we're doing an old-item expunge or the like rather than a single delete/empty op
            info.cascadeIds = DbMailItem.markDeletionTargets(mbox, info.itemIds.getIds(EnumSet.of(Type.MESSAGE, Type.CHAT)), info.modifiedIds);
            if (info.cascadeIds != null) {
                info.modifiedIds.removeAll(info.cascadeIds);
            }
            mbox.purge(Type.CONVERSATION);
            // if there are SOAP listeners, instantiate all modified conversations for notification purposes
            if (!info.modifiedIds.isEmpty() && mbox.hasListeners(Session.Type.SOAP)) {
                for (MailItem conv : mbox.getItemById(info.modifiedIds, Type.CONVERSATION)) {
                    ((Conversation) conv).getSenderList();
                }
            }
        }

        // also delete any conversations whose messages have all been removed
        if (info.cascadeIds != null && !info.cascadeIds.isEmpty()) {
            for (Integer convId : info.cascadeIds) {
                mbox.markItemDeleted(Type.CONVERSATION, convId);
            }
            try {
                DbMailItem.delete(mbox, info.cascadeIds, false);
            } catch (ServiceException se) {
                MailboxErrorUtil.handleCascadeFailure(mbox, info.cascadeIds, se);
            }
            info.itemIds.add(Type.CONVERSATION, info.cascadeIds);
        }

        // deal with index sharing
        if (info.sharedIndex != null && !info.sharedIndex.isEmpty()) {
            DbMailItem.resolveSharedIndex(mbox, info);
        }

        mbox.markOtherItemDirty(info);

        // write a deletion record for later sync
        if (writeTombstones && mbox.isTrackingSync() && !info.itemIds.isEmpty() && !fromDumpster) {
            DbMailItem.writeTombstones(mbox, info.itemIds);
        }

        // don't actually delete the blobs or index entries here; wait until after the commit
    }

    static String getMailopContext(MailItem item) {
        if (item == null || !ZimbraLog.mailop.isInfoEnabled()) {
            return "<undefined>";
        } else if (item instanceof Folder || item instanceof Tag || item instanceof WikiItem) {
            return String.format("%s %s (id=%d)", item.getClass().getSimpleName(), item.getName(), item.getId());
        } else if (item instanceof Contact) {
            String email = ((Contact) item).get(ContactConstants.A_email);
            if (StringUtil.isNullOrEmpty(email)) {
                email = "<undefined>";
            }
            return String.format("%s %s (id=%d)", item.getClass().getSimpleName(), email, item.getId());
        } else {
            return String.format("%s (id=%d)", item.getClass().getSimpleName(), item.getId());
        }
    }

    /** Determines the set of items to be deleted.  Assembles a new
     *  {@link PendingDelete} object encapsulating the data on the items
     *  to be deleted.  If the caller has specified the maximum change
     *  number they know about, this set will also exclude any item for
     *  which the (modification/content) change number is greater.
     *
     * @perms {@link ACL#RIGHT_DELETE} on the item
     * @return A fully-populated <tt>PendingDelete</tt> object. */
    PendingDelete getDeletionInfo() throws ServiceException {
        if (!canAccess(ACL.RIGHT_DELETE)) {
            throw ServiceException.PERM_DENIED("you do not have the required rights on the item");
        }

        Integer id = new Integer(mId);
        PendingDelete info = new PendingDelete();
        info.rootId = mId;
        info.size   = getTotalSize();
        info.itemIds.add(getType(), id);

        if (!inDumpster()) {
            if (mData.unreadCount != 0 && mMailbox.getFlagById(Flag.ID_UNREAD).canTag(this)) {
                info.unreadIds.add(id);
            }
            boolean isDeleted = isTagged(Flag.FlagInfo.DELETED);
            info.folderCounts.put(getFolderId(), new DbMailItem.LocationCount(1, isDeleted ? 1 : 0, info.size));
            for (String tag : mData.getTags()) {
                info.tagCounts.put(tag, new DbMailItem.LocationCount(1, isDeleted ? 1 : 0, info.size));
            }
        }

        // Clean up from blob store and Lucene if:
        //   1) deleting a regular item and dumpster is not in use, OR
        //   2) permantently deleting an item from dumpster
        // In other words, skip the blob/index deletes when soft-deleting item to dumpster.
        if (!getMailbox().dumpsterEnabled() || inDumpster() ||
            mData.folderId == Mailbox.ID_FOLDER_DRAFTS || (inSpam() && !getMailbox().useDumpsterForSpam())) {
            if (getIndexStatus() != IndexStatus.NO) {
                int indexId = getIndexStatus() == IndexStatus.DONE ? mData.indexId : mData.id;
                if (isTagged(Flag.FlagInfo.COPIED)) {
                    info.sharedIndex = Sets.newHashSet(indexId);
                } else {
                    info.indexIds.add(indexId);
                }
            }

            List<MailItem> items = new ArrayList<MailItem>(3);
            items.add(this);
            items.addAll(loadRevisions());
            for (MailItem revision : items) {
                try {
                    info.blobs.add(revision.getBlob());
                } catch (Exception e) {
                    ZimbraLog.mailbox.error("missing blob for id: " + mId + ", change: " + revision.getSavedSequence());
                }
            }
        }

        return info;
    }

    private static final int UNREAD_ITEM_BATCH_SIZE = 500;

    void propagateDeletion(PendingDelete info) throws ServiceException {
        if (!info.unreadIds.isEmpty()) {
            for (int i = 0, count = info.unreadIds.size(); i < count; i += UNREAD_ITEM_BATCH_SIZE) {
                List<Integer> batch = info.unreadIds.subList(i, Math.min(i + UNREAD_ITEM_BATCH_SIZE, count));
                for (UnderlyingData data : DbMailItem.getById(mMailbox, batch, Type.MESSAGE)) {
                    Message msg = (Message) mMailbox.getItem(data);
                    if (msg.isUnread()) {
                        msg.updateUnread(-1, msg.isTagged(Flag.FlagInfo.DELETED) ? -1 : 0);
                    }
                    mMailbox.uncache(msg);
                }
            }
        }

        for (Map.Entry<Integer, DbMailItem.LocationCount> entry : info.folderCounts.entrySet()) {
            Folder folder = mMailbox.getFolderById(entry.getKey());
            DbMailItem.LocationCount lcount = entry.getValue();
            folder.updateSize(-lcount.count, -lcount.deleted, -lcount.size);
        }
        for (Map.Entry<String, DbMailItem.LocationCount> entry : info.tagCounts.entrySet()) {
            Tag tag = mMailbox.getTagByName(entry.getKey());
            DbMailItem.LocationCount lcount = entry.getValue();
            tag.updateSize(-lcount.count, -lcount.deleted);
        }
    }

    void purgeCache(PendingDelete info, boolean purgeItem) throws ServiceException {
        // uncache cascades to uncache children
        if (purgeItem) {
            mMailbox.uncache(this);
        }
    }


    private static final String CUSTOM_META_PREFIX = Metadata.FN_EXTRA_DATA + ".";

    protected boolean trackUserAgentInMetadata() {
        return false;
    }

    Metadata encodeMetadata() {
        Metadata meta = encodeMetadata(new Metadata());
        if (trackUserAgentInMetadata()) {
            OperationContext octxt = getMailbox().getOperationContext();
            if (octxt != null) {
                meta.put(Metadata.FN_USER_AGENT, octxt.getUserAgent());
            }
        }
        return meta;
    }

    abstract Metadata encodeMetadata(Metadata meta);

    static Metadata encodeMetadata(Metadata meta, Color color, int version, CustomMetadataList extended) {
        if (color != null && color.getMappedColor() != DEFAULT_COLOR) {
            meta.put(Metadata.FN_COLOR, color.toMetadata());
        }
        if (version > 1) {
            meta.put(Metadata.FN_VERSION, version);
        }
        if (extended != null) {
            for (Pair<String, String> mpair : extended) {
                meta.put(CUSTOM_META_PREFIX + mpair.getFirst(), mpair.getSecond());
            }
        }
        return meta;
    }

    void decodeMetadata(String metadata) throws ServiceException {
        try {
            decodeMetadata(new Metadata(metadata));
        } catch (ServiceException e) {
            ZimbraLog.mailbox.error("Failed to parse metadata id=%d,type=%s", mId, getType(), e);
            throw e;
        }
    }

    void decodeMetadata(Metadata meta) throws ServiceException {
        if (meta == null)
            return;

        mRGBColor = Color.fromMetadata(meta.getLong(Metadata.FN_COLOR, DEFAULT_COLOR));
        mVersion = (int) meta.getLong(Metadata.FN_VERSION, 1);

        mExtendedData = null;
        for (Map.Entry<String, ?> entry : meta.asMap().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(CUSTOM_META_PREFIX)) {
                if (mExtendedData == null) {
                    mExtendedData = new CustomMetadataList();
                }
                mExtendedData.addSection(key.substring(CUSTOM_META_PREFIX.length()), entry.getValue().toString());
            }
        }
    }


    protected void saveMetadata() throws ServiceException {
        saveMetadata(encodeMetadata().toString());
    }

    protected void saveMetadata(String metadata) throws ServiceException {
        mData.metadataChanged(mMailbox);
        if (ZimbraLog.mailop.isDebugEnabled()) {
            ZimbraLog.mailop.debug("saving metadata for " + getMailopContext(this));
        }
        DbMailItem.saveMetadata(this, metadata);
    }

    protected void saveName() throws ServiceException {
        saveName(getFolderId());
    }

    protected void saveName(int folderId) throws ServiceException {
        DbMailItem.saveName(this, folderId, encodeMetadata());
    }

    protected void saveData(DbMailItem data) throws ServiceException {
        saveData(data, encodeMetadata());
    }

    protected void saveData(DbMailItem data, Metadata metadata) throws ServiceException {
        assert(metadata != null);
        mData.metadataChanged(mMailbox);
        if (ZimbraLog.mailop.isDebugEnabled()) {
            ZimbraLog.mailop.debug("saving data for %s", getMailopContext(this));
        }
        data.update(this, metadata);
    }

    void markMetadataChanged() throws ServiceException {
        saveData(new DbMailItem(mMailbox));
    }

    /**
     * Locks this MailItem with exclusive write lock.
     * When a MailItem is locked, only the user who locked the item
     * can move the item or change the content.
     *
     * @param authuser
     * @throws ServiceException
     */
    void lock(Account authuser) throws ServiceException {
        throw MailServiceException.CANNOT_LOCK(mId);
    }

    /**
     * Unlocks this MailItem.  The user who previously locked
     * the item, or anyone who has admin privilige to this
     * MailItem can perform unlock operation.
     *
     * @param authuser
     * @throws ServiceException
     */
    void unlock(Account authuser) throws ServiceException {
        throw MailServiceException.CANNOT_UNLOCK(mId);
    }

    List<Comment> getComments(SortBy sortBy, int offset, int length) throws ServiceException {
        List<UnderlyingData> listData = DbMailItem.getByParent(this, sortBy, -1, inDumpster());
        ArrayList<Comment> comments = new ArrayList<Comment>();
        for (UnderlyingData data : listData) {
            MailItem item = mMailbox.getItem(data);
            if (item instanceof Comment) {
                comments.add((Comment)item);
            }
        }
        if (comments.size() <= offset) {
            return Collections.<Comment>emptyList();
        }
        int last = length == -1 ? comments.size() : Math.min(comments.size(), offset + length);
        return comments.subList(offset, last);
    }

    Metadata serializeUnderlyingData() {
        Metadata meta = mData.serialize();
        // metadata
        Metadata metaMeta = new Metadata();
        encodeMetadata(metaMeta);
        meta.put(UnderlyingData.FN_METADATA, metaMeta.toString());
        return meta;
    }

    private static final String CN_ID           = "id";
    private static final String CN_TYPE         = "type";
    private static final String CN_PARENT_ID    = "parent_id";
    private static final String CN_FOLDER_ID    = "folder_id";
    private static final String CN_DATE         = "date";
    private static final String CN_SIZE         = "size";
    private static final String CN_REVISION     = "rev";
    private static final String CN_BLOB_DIGEST  = "digest";
    private static final String CN_UNREAD_COUNT = "unread";
    private static final String CN_FLAGS        = "flags";
    private static final String CN_TAGS         = "tags";
    private static final String CN_SUBJECT      = "subject";
    private static final String CN_NAME         = "name";
    private static final String CN_COLOR        = "color";
    private static final String CN_VERSION      = "version";
    private static final String CN_IMAP_ID      = "imap_id";

    protected Objects.ToStringHelper appendCommonMembers(Objects.ToStringHelper helper) {
        helper.add(CN_ID, mId);
        helper.add(CN_TYPE, mData.type);
        if (mData.name != null) {
            helper.add(CN_NAME, mData.name);
        }
        helper.add(CN_UNREAD_COUNT, mData.unreadCount);
        if (mData.getFlags() != 0) {
            helper.add(CN_FLAGS, getFlagString());
        }
        if (mData.getTags().length != 0) {
            helper.add(CN_TAGS, Joiner.on(',').join(mData.getTags()));
        }
        helper.add(CN_FOLDER_ID, mData.folderId);
        helper.add(CN_SIZE, mData.size);
        if (mVersion > 1) {
            helper.add(CN_VERSION, mVersion);
        }
        if (mData.parentId > 0) {
            helper.add(CN_PARENT_ID, mData.parentId);
        }
        if (mRGBColor != null) {
            helper.add(CN_COLOR, mRGBColor.getMappedColor());
        }
        if (mData.getSubject() != null) {
            helper.add(CN_SUBJECT, mData.getSubject());
        }
        if (getDigest() != null) {
            helper.add(CN_BLOB_DIGEST, getDigest());
        }
        if (mData.imapId > 0) {
            helper.add(CN_IMAP_ID, mData.imapId);
        }
        helper.add(CN_DATE, mData.date);
        helper.add(CN_REVISION, mData.modContent);
        return helper;
    }

    public static Set<Integer> toId(Set<? extends MailItem> items) {
        if (items == null)
            return null;

        Set<Integer> result = new HashSet<Integer>(items.size());
        for (MailItem item : items) {
            result.add(item.getId());
        }
        return result;
    }

    public static List<Integer> toId(List<? extends MailItem> items) {
        if (items == null)
            return null;

        List<Integer> result = new ArrayList<Integer>(items.size());
        for (MailItem item : items) {
            result.add(item.getId());
        }
        return result;
    }

    /**
     * Returns a copy of the item with {@link Flag#BITMASK_UNCACHED} set.
     *
     * @return
     * @throws ServiceException
     * @see Mailbox#snapshotItem(MailItem)
     */
    public MailItem snapshotItem() throws ServiceException {
        UnderlyingData data = getUnderlyingData().clone();
        data.setFlag(Flag.FlagInfo.UNCACHED);
        return MailItem.constructItem(mMailbox, data);
    }
}

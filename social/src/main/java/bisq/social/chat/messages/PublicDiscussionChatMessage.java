/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.social.chat.messages;

import bisq.network.p2p.services.data.storage.DistributedData;
import bisq.network.p2p.services.data.storage.MetaData;
import bisq.social.user.ChatUser;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * PublicChatMessage is added as public data to the distributed network storage.
 */
@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class PublicDiscussionChatMessage extends ChatMessage implements DistributedData {
    public PublicDiscussionChatMessage(String channelId,
                                       ChatUser sender,
                                       String text,
                                       Optional<Quotation> quotedMessage,
                                       long date,
                                       boolean wasEdited) {
        this(channelId,
                sender,
                Optional.of(text),
                quotedMessage,
                date,
                wasEdited,
                new MetaData(TimeUnit.DAYS.toMillis(1), 100000, PublicDiscussionChatMessage.class.getSimpleName()));
    }

    protected PublicDiscussionChatMessage(String channelId,
                                          ChatUser sender,
                                          Optional<String> text,
                                          Optional<Quotation> quotedMessage,
                                          long date,
                                          boolean wasEdited,
                                          MetaData metaData) {
        super(channelId,
                sender,
                text,
                quotedMessage,
                date,
                wasEdited,
                metaData);
    }

    public bisq.social.protobuf.ChatMessage toProto() {
        return getChatMessageBuilder().setPublicDiscussionChatMessage(bisq.social.protobuf.PublicDiscussionChatMessage.newBuilder()).build();
    }

    public static PublicDiscussionChatMessage fromProto(bisq.social.protobuf.ChatMessage baseProto) {
        Optional<Quotation> quotedMessage = baseProto.hasQuotedMessage() ?
                Optional.of(Quotation.fromProto(baseProto.getQuotedMessage())) :
                Optional.empty();
        return new PublicDiscussionChatMessage(
                baseProto.getChannelId(),
                ChatUser.fromProto(baseProto.getAuthor()),
                Optional.of(baseProto.getText()),
                quotedMessage,
                baseProto.getDate(),
                baseProto.getWasEdited(),
                MetaData.fromProto(baseProto.getMetaData()));
    }

    @Override
    public MetaData getMetaData() {
        return metaData;
    }

    @Override
    public boolean isDataInvalid() {
        return false;
    }
}
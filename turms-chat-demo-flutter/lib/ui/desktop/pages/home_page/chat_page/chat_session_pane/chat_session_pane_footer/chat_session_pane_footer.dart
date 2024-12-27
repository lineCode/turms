import 'dart:async';
import 'dart:io';

import 'package:dotted_border/dotted_border.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_symbols_icons/symbols.dart';
import 'package:super_clipboard/super_clipboard.dart';
import 'package:super_drag_and_drop/super_drag_and_drop.dart';

import '../../../../../../../domain/conversation/models/conversation.dart';
import '../../../../../../../domain/message/models/message_delivery_status.dart';
import '../../../../../../../domain/message/services/message_service.dart';
import '../../../../../../../domain/user/view_models/logged_in_user_info_view_model.dart';
import '../../../../../../../infra/built_in_types/built_in_type_helpers.dart';
import '../../../../../../../infra/io/data_reader_file_adaptor.dart';
import '../../../../../../../infra/io/file_utils.dart';
import '../../../../../../../infra/logging/logger.dart';
import '../../../../../../../infra/random/random_utils.dart';
import '../../../../../../l10n/app_localizations.dart';
import '../../../../../../l10n/view_models/app_localizations_view_model.dart';
import '../../../../../../themes/app_theme_extension.dart';
import '../../../../../../themes/sizes.dart';
import '../../../../../components/giphy/client/models/gif.dart';
import '../../../../../components/index.dart';
import '../../view_models/selected_conversation_view_model.dart';
import '../attachment.dart';
import '../message.dart';
import '../single_chat_history_page/single_chat_history_page.dart';
import '../sticker_picker/sticker_picker.dart';
import 'message_editor.dart';

class ChatSessionPaneFooter extends ConsumerStatefulWidget {
  const ChatSessionPaneFooter({super.key});

  @override
  ConsumerState<ChatSessionPaneFooter> createState() =>
      _ChatSessionPaneFooterState();
}

class _ChatSessionPaneFooterState extends ConsumerState<ChatSessionPaneFooter> {
  final List<DataReaderFile> _localFiles = [];
  bool _dragging = false;

  late EmojiTextEditingController _editorController;
  late FocusNode _editorFocusNode;

  late TPopupController _stickerPickerPopupController;

  Conversation? _conversation;

  @override
  void initState() {
    super.initState();
    _editorController = EmojiTextEditingController()
      ..addListener(() {
        // The send button will be enabled when the text is not empty,
        // so we need to update the state.
        setState(() {});
      });
    _editorFocusNode = FocusNode();
    _stickerPickerPopupController = TPopupController();
  }

  @override
  void dispose() {
    _editorController.dispose();
    _editorFocusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = context.theme;
    final appThemeExtension = theme.appThemeExtension;
    final appLocalizations = ref.watch(appLocalizationsViewModel);
    final newConversation = ref.watch(selectedConversationViewModel);
    final currentConversation = _conversation;
    if (newConversation?.id != currentConversation?.id) {
      SchedulerBinding.instance.addPostFrameCallback((_) {
        if (currentConversation != null) {
          final previousDraft = currentConversation.draft;
          final draft = _getEditorDocument();
          if (draft.isEmpty) {
            if (previousDraft != null) {
              currentConversation.draft = null;
              ref
                  .read(selectedConversationViewModel.notifier)
                  .notifyListeners();
            }
          } else {
            if (draft != previousDraft) {
              currentConversation.draft = draft;
              ref
                  .read(selectedConversationViewModel.notifier)
                  .notifyListeners();
            }
          }
        }
        final currentDraft = newConversation?.draft;
        if (currentDraft?.isBlank ?? true) {
          _editorController.text = '';
        } else {
          _editorController.text = currentDraft!;
        }
        _editorFocusNode.requestFocus();
        setState(() {});
      });
      _conversation = newConversation;
    }
    return _buildView(theme, appThemeExtension, appLocalizations);
  }

  String _getEditorDocument() => _editorController.text.trim();

  bool _tryAddNewFile(List<DataReaderFile> newFiles) {
    var hasNewFile = false;
    for (final newFile in newFiles) {
      // Exclude directory (The size of directory is null).
      if (newFile.fileSize == null) {
        continue;
      }
      if (!_localFiles.any((existingFile) =>
          existingFile.fileName == newFile.fileName &&
          existingFile.fileSize == newFile.fileSize)) {
        hasNewFile = true;
        _localFiles.add(newFile);
      }
    }
    return hasNewFile;
  }

  void _onDropEnter() {
    _dragging = true;
    setState(() {});
  }

  void _onDropLeave() {
    _dragging = false;
    setState(() {});
  }

  DropOperation _onDropOver(DropOverEvent event) {
    if (event.session.allowedOperations.contains(DropOperation.copy)) {
      return DropOperation.copy;
    } else {
      return DropOperation.none;
    }
  }

  Future<void> _onPerformDrop(PerformDropEvent event) async {
    final futures = event.session.items.map((item) {
      final completer = Completer<DataReaderFile>();
      item.dataReader!
          .getFile(null, completer.complete, onError: completer.completeError);
      return completer.future;
    });
    final newFiles = await Future.wait(futures);
    if (_tryAddNewFile(newFiles)) {
      setState(() {});
    }
  }

  void _insertEmoji(String emoji) {
    final selection = _editorController.selection;
    final start = selection.start;
    final end = selection.end;
    final text = _editorController.text;
    String prefix;
    if (start < 0) {
      prefix = text + emoji;
      _editorController.text = prefix;
    } else {
      prefix = start == 0 ? emoji : text.substring(0, start) + emoji;
      if (end >= text.length) {
        _editorController.text = prefix;
      } else {
        _editorController.text = prefix + text.substring(end);
      }
    }
    _editorController.selection =
        TextSelection.collapsed(offset: prefix.length);

    _editorFocusNode.requestFocus();
    _stickerPickerPopupController.hidePopover?.call();
    setState(() {});
  }

  Future<void> _sendMessage([String? msg]) async {
    final text = msg ?? _getEditorDocument();
    if (text.isBlank) {
      return;
    }
    final now = DateTime.now();
    final fakeMessageId = -RandomUtils.nextUniquePositiveInt64();
    // Note that: the timestamp may be different from the one the recipients received.
    // TODO: Use the server time for reference,
    //  especially when the device time is not correct.
    final DateTime tempTimestamp;
    final lastMessageTimestamp = _conversation?.messages.lastOrNull?.timestamp;
    if (lastMessageTimestamp == null ||
        lastMessageTimestamp.compareTo(now) < 0) {
      tempTimestamp = now;
    } else {
      tempTimestamp = lastMessageTimestamp.add(const Duration(milliseconds: 1));
    }
    final isGroupMessage = _conversation is GroupConversation;
    final loggedInUserId = ref.read(loggedInUserViewModel)!.userId;
    final (groupId, recipientId) = switch (_conversation!) {
      GroupConversation c => (c.contact.groupId, null),
      UserConversation c => (null, c.contact.userId),
      SystemConversation c => (null, loggedInUserId)
    };
    final message = ChatMessage.parse(
        text: text,
        messageId: fakeMessageId,
        groupId: groupId,
        recipientId: recipientId,
        senderId: loggedInUserId,
        sentByMe: true,
        isGroupMessage: isGroupMessage,
        timestamp: tempTimestamp,
        status: MessageDeliveryStatus.delivering);
    final selectedConversationController =
        ref.read(selectedConversationViewModel.notifier)..addMessage(message);
    _editorController.text = '';
    setState(() {});

    final sentMessage =
        await ref.read(messageServiceProvider)!.sendMessage(text, message);

    // TODO: handle the case when the controller has already been changed.
    selectedConversationController.replaceMessage(
        fakeMessageId,
        message.copyWith(
            messageId: sentMessage.messageId,
            status: sentMessage.status,
            // Note that this will update the timestamp UI of the message
            // if the received timestamp is different from the fake one,
            // which is expected to ensure the timestamp is consistent with the server and recipients.
            timestamp: sentMessage.timestamp));
  }

  void _sendImage(AppLocalizations appLocalizations, String originalUrl,
      String thumbnailUrl, int width, int height) {
    try {
      final originalUri = Uri.parse(originalUrl);
      originalUrl = originalUri.origin + originalUri.path;
    } catch (e) {
      TToast.showToast(context, appLocalizations.failedToSendImageInvalidUrl);
      logger.error(
          'Failed to send image. The original URL is invalid: $originalUrl', e);
      return;
    }
    try {
      final thumbnailUri = Uri.parse(thumbnailUrl);
      thumbnailUrl = thumbnailUri.origin + thumbnailUri.path;
    } catch (e) {
      TToast.showToast(context, appLocalizations.failedToSendImageInvalidUrl);
      logger.error(
          'Failed to send image. The thumbnail URL is invalid: $thumbnailUrl',
          e);
      return;
    }
    final text = MessageService.encodeImageMessage(
        originalUrl: originalUrl,
        thumbnailUrl: thumbnailUrl,
        width: width,
        height: height);
    _sendMessage(text);
  }

  void _removeFiles(DataReaderFile file) {
    _localFiles.remove(file);
    setState(() {});
  }

  Future<void> _pickFile() async {
    final result = await FileUtils.pickFile();
    if (result == null) {
      return;
    }
    final path = result.files.singleOrNull?.path;
    if (path == null) {
      return;
    }
    final file = File(path);
    _tryAddNewFile([DataReaderFileValueAdapter(file)]);
    setState(() {});
  }
}

extension _ChatSessionPaneFooterView on _ChatSessionPaneFooterState {
  Widget _buildView(ThemeData theme, AppThemeExtension appThemeExtension,
          AppLocalizations appLocalizations) =>
      Stack(
        children: [
          DropRegion(
              formats: Formats.standardFormats,
              onDropOver: _onDropOver,
              onDropEnter: (event) {
                _onDropEnter();
              },
              onDropLeave: (event) {
                _onDropLeave();
              },
              onPerformDrop: _onPerformDrop,
              child: Padding(
                padding: Sizes.paddingV8H16,
                child: _buildEditor(theme, appThemeExtension, appLocalizations),
              )),
          _buildDropZoneMask(theme, appLocalizations)
        ],
      );

  Widget _buildEditor(ThemeData theme, AppThemeExtension appThemeExtension,
          AppLocalizations appLocalizations) =>
      Column(
        children: [
          Expanded(
            child: ColoredBox(
              color: appThemeExtension.homePageBackgroundColor,
              child: CallbackShortcuts(
                bindings: {
                  const SingleActivator(LogicalKeyboardKey.enter): _sendMessage
                },
                child: MessageEditor(
                  controller: _editorController,
                  conversation: _conversation!,
                  autofocus: true,
                  focusNode: _editorFocusNode,
                  contentPadding: const EdgeInsets.only(top: 8),
                ),
              ),
            ),
          ),
          if (_localFiles.isNotEmpty)
            Align(
              alignment: Alignment.topLeft,
              child: Wrap(
                runSpacing: 4,
                spacing: 4,
                children: _localFiles
                    .map((file) => Attachment(
                          fileName: file.fileName ?? '',
                          onRemoveAttachmentTapped: () {
                            _removeFiles(file);
                          },
                        ))
                    .toList(growable: false),
              ),
            ),
          const SizedBox(
            height: 12,
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  TPopup(
                      controller: _stickerPickerPopupController,
                      targetAnchor: Alignment.topCenter,
                      followerAnchor: Alignment.bottomCenter,
                      offset: const Offset(-5, -5),
                      target: TIconButton(
                        iconData: Symbols.emoji_emotions_rounded,
                        iconColor: Colors.black54,
                        iconColorHovered: Colors.black87,
                        iconColorPressed: theme.primaryColor,
                        tooltip: appLocalizations.sticker,
                      ),
                      follower: StickerPicker(
                        onGiphyGifSelected: (GiphyGif value) {
                          final images = value.images;
                          final original = images?.original;
                          final previewWebp = images?.previewWebp;
                          if (original == null || previewWebp == null) {
                            return;
                          }
                          _sendImage(
                              appLocalizations,
                              original.webp ?? original.url,
                              previewWebp.url,
                              int.parse(original.width),
                              int.parse(original.height));
                        },
                        onEmojiSelected: _insertEmoji,
                      )),
                  TIconButton(
                    iconData: Symbols.folder_rounded,
                    iconColor: Colors.black54,
                    iconColorHovered: Colors.black87,
                    iconColorPressed: theme.primaryColor,
                    tooltip: appLocalizations.sticker,
                    onTap: () async {
                      // TODO
                      final file = await _pickFile();
                    },
                  ),
                  TIconButton(
                    iconData: Symbols.history_rounded,
                    iconColor: Colors.black54,
                    iconColorHovered: Colors.black87,
                    iconColorPressed: theme.primaryColor,
                    tooltip: appLocalizations.chatHistory,
                    onTap: () async {
                      await showSingleChatHistoryDialog(
                          context: context, conversation: _conversation!);
                    },
                  )
                ],
              ),
              TIconButton(
                  iconData: Symbols.send_rounded,
                  iconColor: Colors.black54,
                  iconColorHovered: theme.primaryColor,
                  tooltip: appLocalizations.send,
                  disabled: _editorController.text.isEmpty,
                  onTap: _sendMessage)
            ],
          ),
        ],
      );

  IgnorePointer _buildDropZoneMask(
          ThemeData theme, AppLocalizations localizations) =>
      // Ignore pointer to not obstruct "DropRegion"
      IgnorePointer(
          child: AnimatedOpacity(
              opacity: _dragging ? 1.0 : 0.0,
              duration: const Duration(milliseconds: 100),
              child: Padding(
                padding: const EdgeInsets.only(right: 4, bottom: 4),
                child: DottedBorder(
                  borderType: BorderType.RRect,
                  dashPattern: [12, 10],
                  color: theme.primaryColor,
                  strokeWidth: 2,
                  radius: const Radius.circular(8),
                  child: ClipRRect(
                    child: ColoredBox(
                      color: Colors.white.withValues(alpha: 0.6),
                      child: Center(
                        child: Text(
                          localizations.dropFilesHere,
                          style: TextStyle(color: theme.primaryColor),
                        ),
                      ),
                    ),
                  ),
                ),
              )));
}

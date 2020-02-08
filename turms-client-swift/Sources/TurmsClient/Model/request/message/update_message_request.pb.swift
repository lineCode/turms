// DO NOT EDIT.
//
// Generated by the Swift generator plugin for the protocol buffer compiler.
// Source: request/message/update_message_request.proto
//
// For information on using the generated types, please see the documentation:
//   https://github.com/apple/swift-protobuf/

import Foundation
import SwiftProtobuf

// If the compiler emits an error on this type, it is because this file
// was generated by a version of the `protoc` Swift plug-in that is
// incompatible with the version of SwiftProtobuf to which you are linking.
// Please ensure that your are building against the same version of the API
// that was used to generate this file.
fileprivate struct _GeneratedWithProtocGenSwiftVersion: SwiftProtobuf.ProtobufAPIVersionCheck {
  struct _2: SwiftProtobuf.ProtobufAPIVersion_2 {}
  typealias Version = _2
}

public struct UpdateMessageRequest {
  // SwiftProtobuf.Message conformance is added in an extension below. See the
  // `Message` and `Message+*Additions` files in the SwiftProtobuf library for
  // methods supported on all messages.

  public var messageID: Int64 {
    get {return _storage._messageID}
    set {_uniqueStorage()._messageID = newValue}
  }

  public var isSystemMessage: SwiftProtobuf.Google_Protobuf_BoolValue {
    get {return _storage._isSystemMessage ?? SwiftProtobuf.Google_Protobuf_BoolValue()}
    set {_uniqueStorage()._isSystemMessage = newValue}
  }
  /// Returns true if `isSystemMessage` has been explicitly set.
  public var hasIsSystemMessage: Bool {return _storage._isSystemMessage != nil}
  /// Clears the value of `isSystemMessage`. Subsequent reads from it will return its default value.
  public mutating func clearIsSystemMessage() {_uniqueStorage()._isSystemMessage = nil}

  public var text: SwiftProtobuf.Google_Protobuf_StringValue {
    get {return _storage._text ?? SwiftProtobuf.Google_Protobuf_StringValue()}
    set {_uniqueStorage()._text = newValue}
  }
  /// Returns true if `text` has been explicitly set.
  public var hasText: Bool {return _storage._text != nil}
  /// Clears the value of `text`. Subsequent reads from it will return its default value.
  public mutating func clearText() {_uniqueStorage()._text = nil}

  public var records: [Data] {
    get {return _storage._records}
    set {_uniqueStorage()._records = newValue}
  }

  public var recallDate: SwiftProtobuf.Google_Protobuf_Int64Value {
    get {return _storage._recallDate ?? SwiftProtobuf.Google_Protobuf_Int64Value()}
    set {_uniqueStorage()._recallDate = newValue}
  }
  /// Returns true if `recallDate` has been explicitly set.
  public var hasRecallDate: Bool {return _storage._recallDate != nil}
  /// Clears the value of `recallDate`. Subsequent reads from it will return its default value.
  public mutating func clearRecallDate() {_uniqueStorage()._recallDate = nil}

  public var readDate: SwiftProtobuf.Google_Protobuf_Int64Value {
    get {return _storage._readDate ?? SwiftProtobuf.Google_Protobuf_Int64Value()}
    set {_uniqueStorage()._readDate = newValue}
  }
  /// Returns true if `readDate` has been explicitly set.
  public var hasReadDate: Bool {return _storage._readDate != nil}
  /// Clears the value of `readDate`. Subsequent reads from it will return its default value.
  public mutating func clearReadDate() {_uniqueStorage()._readDate = nil}

  public var unknownFields = SwiftProtobuf.UnknownStorage()

  public init() {}

  fileprivate var _storage = _StorageClass.defaultInstance
}

// MARK: - Code below here is support for the SwiftProtobuf runtime.

fileprivate let _protobuf_package = "im.turms.proto"

extension UpdateMessageRequest: SwiftProtobuf.Message, SwiftProtobuf._MessageImplementationBase, SwiftProtobuf._ProtoNameProviding {
  public static let protoMessageName: String = _protobuf_package + ".UpdateMessageRequest"
  public static let _protobuf_nameMap: SwiftProtobuf._NameMap = [
    1: .standard(proto: "message_id"),
    2: .standard(proto: "is_system_message"),
    3: .same(proto: "text"),
    4: .same(proto: "records"),
    5: .standard(proto: "recall_date"),
    6: .standard(proto: "read_date"),
  ]

  fileprivate class _StorageClass {
    var _messageID: Int64 = 0
    var _isSystemMessage: SwiftProtobuf.Google_Protobuf_BoolValue? = nil
    var _text: SwiftProtobuf.Google_Protobuf_StringValue? = nil
    var _records: [Data] = []
    var _recallDate: SwiftProtobuf.Google_Protobuf_Int64Value? = nil
    var _readDate: SwiftProtobuf.Google_Protobuf_Int64Value? = nil

    static let defaultInstance = _StorageClass()

    private init() {}

    init(copying source: _StorageClass) {
      _messageID = source._messageID
      _isSystemMessage = source._isSystemMessage
      _text = source._text
      _records = source._records
      _recallDate = source._recallDate
      _readDate = source._readDate
    }
  }

  fileprivate mutating func _uniqueStorage() -> _StorageClass {
    if !isKnownUniquelyReferenced(&_storage) {
      _storage = _StorageClass(copying: _storage)
    }
    return _storage
  }

  public mutating func decodeMessage<D: SwiftProtobuf.Decoder>(decoder: inout D) throws {
    _ = _uniqueStorage()
    try withExtendedLifetime(_storage) { (_storage: _StorageClass) in
      while let fieldNumber = try decoder.nextFieldNumber() {
        switch fieldNumber {
        case 1: try decoder.decodeSingularInt64Field(value: &_storage._messageID)
        case 2: try decoder.decodeSingularMessageField(value: &_storage._isSystemMessage)
        case 3: try decoder.decodeSingularMessageField(value: &_storage._text)
        case 4: try decoder.decodeRepeatedBytesField(value: &_storage._records)
        case 5: try decoder.decodeSingularMessageField(value: &_storage._recallDate)
        case 6: try decoder.decodeSingularMessageField(value: &_storage._readDate)
        default: break
        }
      }
    }
  }

  public func traverse<V: SwiftProtobuf.Visitor>(visitor: inout V) throws {
    try withExtendedLifetime(_storage) { (_storage: _StorageClass) in
      if _storage._messageID != 0 {
        try visitor.visitSingularInt64Field(value: _storage._messageID, fieldNumber: 1)
      }
      if let v = _storage._isSystemMessage {
        try visitor.visitSingularMessageField(value: v, fieldNumber: 2)
      }
      if let v = _storage._text {
        try visitor.visitSingularMessageField(value: v, fieldNumber: 3)
      }
      if !_storage._records.isEmpty {
        try visitor.visitRepeatedBytesField(value: _storage._records, fieldNumber: 4)
      }
      if let v = _storage._recallDate {
        try visitor.visitSingularMessageField(value: v, fieldNumber: 5)
      }
      if let v = _storage._readDate {
        try visitor.visitSingularMessageField(value: v, fieldNumber: 6)
      }
    }
    try unknownFields.traverse(visitor: &visitor)
  }

  public static func ==(lhs: UpdateMessageRequest, rhs: UpdateMessageRequest) -> Bool {
    if lhs._storage !== rhs._storage {
      let storagesAreEqual: Bool = withExtendedLifetime((lhs._storage, rhs._storage)) { (_args: (_StorageClass, _StorageClass)) in
        let _storage = _args.0
        let rhs_storage = _args.1
        if _storage._messageID != rhs_storage._messageID {return false}
        if _storage._isSystemMessage != rhs_storage._isSystemMessage {return false}
        if _storage._text != rhs_storage._text {return false}
        if _storage._records != rhs_storage._records {return false}
        if _storage._recallDate != rhs_storage._recallDate {return false}
        if _storage._readDate != rhs_storage._readDate {return false}
        return true
      }
      if !storagesAreEqual {return false}
    }
    if lhs.unknownFields != rhs.unknownFields {return false}
    return true
  }
}
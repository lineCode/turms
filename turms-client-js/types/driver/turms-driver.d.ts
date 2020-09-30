import { im } from "../model/proto-bundle";
import { ParsedNotification } from "../model/parsed-notification";
import UserLocation from "../model/user-location";
import { SessionDisconnectInfo } from "../model/session-disconnect-info";
import ConnectionService from "./service/connection-service";
import SessionService, { SessionStatus } from "./service/session-service";
import TurmsNotification = im.turms.proto.TurmsNotification;
import UserStatus = im.turms.proto.UserStatus;
import DeviceType = im.turms.proto.DeviceType;
export default class TurmsDriver {
    private _queryReasonWhenLoginFailed;
    private _queryReasonWhenDisconnected;
    private _onSessionConnected;
    private _onSessionDisconnected;
    private _onSessionClosed;
    private _stateStore;
    private _connectionService;
    private _heartbeatService;
    private _messageService;
    private _reasonService;
    private _sessionService;
    constructor(wsUrl?: string, connectTimeout?: number, requestTimeout?: number, minRequestInterval?: number, heartbeatInterval?: number, httpUrl?: string, queryReasonWhenLoginFailed?: boolean, queryReasonWhenDisconnected?: boolean, storePassword?: boolean);
    initConnectionService(wsUrl?: string, httpUrl?: string, connectTimeout?: number, storePassword?: boolean): ConnectionService;
    initSessionService(): SessionService;
    getStatus(): SessionStatus;
    isConnected(): boolean;
    isClosed(): boolean;
    set onSessionConnected(listener: () => void);
    set onSessionDisconnected(listener: (disconnectInfo: SessionDisconnectInfo) => void);
    set onSessionClosed(listener: (disconnectInfo: SessionDisconnectInfo) => void);
    startHeartbeat(): void;
    stopHeartbeat(): void;
    resetHeartbeat(): void;
    sendHeartbeat(): Promise<void>;
    connect(userId: string, password: string, deviceType?: DeviceType, userOnlineStatus?: UserStatus, location?: UserLocation): Promise<void>;
    reconnect(host?: string): Promise<void>;
    disconnect(): Promise<void>;
    send(message: im.turms.proto.ITurmsRequest): Promise<TurmsNotification>;
    addOnNotificationListener(listener: ((notification: ParsedNotification) => void)): void;
    private _onConnectionConnected;
    private _onConnectionDisconnected;
    private _triggerOnLoginFailure;
    private _triggerOnSessionDisconnected;
    private _onMessage;
}

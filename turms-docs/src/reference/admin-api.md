# Admin API

The Turms server-side API documentation follows the [OpenAPI 3.0](https://swagger.io/specification) standard and provides the current server-side OpenAPI interface documentation externally via an HTTP service.

If you need to consult the API interface documentation, you can access the API interface at http://localhost:端口号/openapi/ui after starting the Turms server. If you need the JSON format data of the API interface, you can get it by visiting http://localhost:端口号/openapi/docs. The default port number for the turms-gateway administrator HTTP server is 9510, while the turms-service uses port 8510.

Note: When deploying the Turms server to a production environment, it is usually not necessary to open the Admin API port of the Turms server to the public network to avoid unwanted attacks.

## Interface Design Guidelines

In order to make the interface as the name suggests and to ensure developers can understand it at a glance, Turms' Admin API interface design refers to the [RESTful](https://en.wikipedia.org/wiki/Representational_state_transfer) design style and has been further optimized and Uniformity, specifically following the following guidelines.

* The path portion of the URL represents the target resource, such as `/users/relationships`; or the representation of the resource, such as `/users/relationships/page` indicating that the resource is returned in paged form. A URI has and may only return a Response in one format.

* POST method for Create resources, DELETE method for Delete resources, PUT method for Update resources, GET method for Query resources, and the more specific HEAD method for Check resources (similar to GET but without the Response body, interacting only through HTTP status codes)

* The requested Query string is used to locate the resource, such as `?ids=1,2,3`; or an additional directive, such as `?reset=true`

  Note: Unlike the RESTful style, the Turms server does not use the request URL path (Path) for resource location. For example, `GET /flight-recordings/jfr` to download JFR file interface, in RESTful style it should be `GET /flight-recordings/jfr/{id}`, but in Turms server it is `GET /flight-recordings/jfr?id={id }`

* The Body of the request is used to describe the data to be created or updated

## Objects that use the management interface

* HTTP(S) request from your front-end management system or back-end server to make the call

* The [turms-admin](https://github.com/turms-im/turms/tree/develop/turms-admin) used by the administrator backend to manage web projects

Note: The administration interface is not for end-users, but for your team to make internal calls. So normally you don't need to open external IP and port for turms-service server.

## Categories

### Non-business Related Categories

#### Monitoring

| Type                        | Controller                | Path               | Supported Servers |
| :-------------------------- | :------------------------ | ------------------ | ----------------- |
| Log Management              | LogController             | /logs              | All               |
| Metrics Management          | MetricsController         | /metrics           | All               |
| Flight Recording Management | FlightRecordingController | /flight-recordings | All               |

#### Plugin

| Type              | Controller       | Path     | Supported Servers |
| :---------------- | :--------------- | -------- | ----------------- |
| Plugin Management | PluginController | /plugins | All               |

#### Administrator

| Type                  | Controller          | Path          | Supported Servers | Notes                                                        |
| :-------------------- | :------------------ | ------------- | ----------------- | ------------------------------------------------------------ |
| Admin Management      | AdminController     | /admins       | turms-service     | Each Turms cluster has a default account with the role `ROOT` and the account name and password `turms` |
| Admin Role Management | AdminRoleController | /admins/roles | turms-service     | By default, each Turms cluster has a super administrator role with the role `ROOT` and all privileges |

#### Cluster

| Type                             | Controller        | Path              | Supported Servers |
| :------------------------------- | :---------------- | ----------------- | ----------------- |
| Cluster Node Management          | MemberController  | /cluster/members  | turms-service     |
| Cluster Configuration Management | SettingController | /cluster/settings | turms-service     |

#### Blocklist

| Type                      | Controller              | Path                   | Supported Servers |
| :------------------------ | :---------------------- | ---------------------- | ----------------- |
| IP Blocklist Management   | IpBlocklistController   | /blocked-clients/ips   | turms-service     |
| User Blocklist Management | UserBlocklistController | /blocked-clients/users | turms-service     |

#### User Session

| Type                    | Controller        | Path      | Supported Servers |
| :---------------------- | :---------------- | --------- | ----------------- |
| User Session Management | SessionController | /sessions | turms-gateway     |

### Business-related Categories

All API ports in the following table exist only on the turms-service server side. These API ports are not available on the turms-gateway server side.

#### User

| Type                               | Controller                      | Path                                 |
|:-----------------------------------|:--------------------------------|--------------------------------------|
| User Information Management        | UserController                  | /users                               |
| User Online Info Management        | UserOnlineInfoController        | /users/online-infos                  |
| User Role Management               | UserRoleController              | /users/roles                         |
| User Relationship Management       | UserRelationshipController      | /users/relationships                 |
| User Relationship Group Management | UserRelationshipGroupController | /users/relationships/groups          |
| User Friend Request Management     | UserFriendRequestController     | /users/relationships/friend-requests |

#### Group

| Type                          | Controller                 | Path                  |
| ----------------------------- | -------------------------- | --------------------- |
| Group Management              | GroupController            | /groups               |
| Group Type Management         | GroupTypeController        | /groups/types         |
| Group Question Management     | GroupQuestionController    | /groups/questions     |
| Group Member Management       | GroupMemberController      | /groups/members       |
| Group Blocklist Management    | GroupBlocklistController   | /groups/blocked-users |
| Group Invitation Management   | GroupInvitationController  | /groups/invitations   |
| Group Join Request Management | GroupJoinRequestController | /groups/join-requests |

#### Chat Session

| Type                    | Controller             | Path           |
| ----------------------- | ---------------------- | -------------- |
| Conversation Management | ConversationController | /conversations |

#### Message Classes

| Type               | Controller        | Path      |
| ------------------ | ----------------- | --------- |
| Message Management | MessageController | /messages |

## Statistics

The statistics-related interfaces currently exposed to the public are mostly Legacy APIs, which are not recommended. We will adjust and refactor them later. Please refer to the chapter [Data Analysis](https://turms-im.github.io/docs/server/module/data-analytics) for specific reasons.

## Admin API Security

Every HTTP request sent by a user to Turms server will go through the authentication and authorization process of Turms server, which can be found in [Administrator Security](https://turms-im.github.io/docs/server/module/security#%E7%AE%A1%E7%90%86%E5%91%98%E5%AE%89%E5%85%A8).
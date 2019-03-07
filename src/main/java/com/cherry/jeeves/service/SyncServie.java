package com.cherry.jeeves.service;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cherry.jeeves.domain.response.BatchGetContactResponse;
import com.cherry.jeeves.domain.response.SyncCheckResponse;
import com.cherry.jeeves.domain.response.SyncResponse;
import com.cherry.jeeves.domain.response.VerifyUserResponse;
import com.cherry.jeeves.domain.shared.ChatRoomDescription;
import com.cherry.jeeves.domain.shared.Contact;
import com.cherry.jeeves.domain.shared.Message;
import com.cherry.jeeves.domain.shared.RecommendInfo;
import com.cherry.jeeves.domain.shared.VerifyUser;
import com.cherry.jeeves.enums.AppMessageType;
import com.cherry.jeeves.enums.MessageType;
import com.cherry.jeeves.enums.RetCode;
import com.cherry.jeeves.enums.Selector;
import com.cherry.jeeves.enums.StatusNotifyCode;
import com.cherry.jeeves.utils.WechatUtils;

@Component
public class SyncServie {
    private static final Logger logger = LoggerFactory.getLogger(SyncServie.class);
    @Autowired
    private CacheService cacheService;
    @Autowired
    private WechatHttpServiceInternal wechatHttpService;
    @Autowired(required = false)
    private MessageHandler messageHandler;

    @Value("${wechat.url.get_msg_img}")
    private String WECHAT_URL_GET_MSG_IMG;
    @Value("${wechat.url.get_voice}")
    private String WECHAT_URL_GET_VOICE;
    @Value("${wechat.url.get_video}")
    private String WECHAT_URL_GET_VIDEO;
    @Value("${wechat.url.get_media}")
    private String WECHAT_URL_GET_MEDIA;
    
    private final static String RED_PACKET_CONTENT = "收到红包，请在手机上查看";

    @PostConstruct
    public void setMessageHandler() {
        if (messageHandler == null) {
            this.messageHandler = new DefaultMessageHandler();
        }
    }

    public void listen() throws IOException, URISyntaxException {
        SyncCheckResponse syncCheckResponse = wechatHttpService.syncCheck();
        if(syncCheckResponse == null)
        	return;
        
        int retCode = syncCheckResponse.getRetcode();
        int selector = syncCheckResponse.getSelector();
        logger.info(String.format("[SYNCCHECK] retcode = %s, selector = %s", retCode, selector));
        if (retCode == RetCode.NORMAL.getCode()) {
            //有新消息
        	if (selector == Selector.NORMAL.getCode()) {
            	return;
            } else if (selector == Selector.NEW_MESSAGE.getCode()) {
                onNewMessage();
            } else {
                sync();
            }
        } else if(retCode == RetCode.LOGOUT1.getCode() || retCode == RetCode.LOGOUT2.getCode() || retCode == RetCode.LOGOUT3.getCode()) {
        	this.messageHandler.onLogout(cacheService.getOwner());
        	cacheService.setAlive(false);
        	 logger.info(String.format("[Logout] syncCheckResponse ret =  %s", retCode));
//        	throw new WechatException("syncCheckResponse ret = " + retCode);
        }
    }

    private SyncResponse sync() throws IOException {
        SyncResponse syncResponse = wechatHttpService.sync();
        WechatUtils.checkBaseResponse(syncResponse);
        cacheService.setSyncKey(syncResponse.getSyncKey());
        cacheService.setSyncCheckKey(syncResponse.getSyncCheckKey());
//        System.out.println("sync 返回[SyncKey]:"+cacheService.getSyncKey().toString());
//        System.out.println("sync 返回[SyncCheckKey]:"+cacheService.getSyncCheckKey().toString());
        //mod包含新增和修改
        if (syncResponse.getModContactCount() > 0) {
        	new Thread(() ->{
        		onContactsModified(syncResponse.getModContactList());
        	}).start();
        }
        //del->联系人移除
        if (syncResponse.getDelContactCount() > 0) {
        	new Thread(() ->{
        		onContactsDeleted(syncResponse.getDelContactList());
        	}).start();
        }
        return syncResponse;
    }

    private void acceptFriendInvitation(RecommendInfo info) throws IOException, URISyntaxException {
        VerifyUser user = new VerifyUser();
        user.setValue(info.getUserName());
        user.setVerifyUserTicket(info.getTicket());
        VerifyUserResponse verifyUserResponse = wechatHttpService.acceptFriend(new VerifyUser[]{user}
        );
        WechatUtils.checkBaseResponse(verifyUserResponse);
    }

    private boolean isMessageFromIndividual(Message message) {
        return message.getFromUserName() != null
                && message.getFromUserName().startsWith("@")
                && !message.getFromUserName().startsWith("@@");
    }

    private boolean isMessageFromChatRoom(Message message) {
        return (message.getFromUserName() != null && message.getFromUserName().startsWith("@@"))
        		|| (message.getToUserName() != null && message.getToUserName().startsWith("@@"));
    }

    private void onNewMessage() throws IOException, URISyntaxException {
        SyncResponse syncResponse = sync();
        
        new Thread(() ->{
        	
        	if (messageHandler == null) {
        		return;
        	}
        	for (Message message : syncResponse.getAddMsgList()) {
        		try {
	        		//文本消息
	        		if (message.getMsgType() == MessageType.TEXT.getCode()) {
	        			cacheService.getContactNamesWithUnreadMessage().add(message.getFromUserName());
	        			//群
	        			if (isMessageFromChatRoom(message)) {
	        				messageHandler.onReceivingChatRoomTextMessage(message);
	        			}
	        			//个人
	        			else if (isMessageFromIndividual(message)) {
	        				messageHandler.onReceivingPrivateTextMessage(message);
	        			}
	        			
	        		} 
	        		//图片
	        		else if (message.getMsgType() == MessageType.IMAGE.getCode() || message.getAppMsgType() == AppMessageType.IMG.getCode()) {
	        			cacheService.getContactNamesWithUnreadMessage().add(message.getFromUserName());
	        			String fullImageUrl = String.format(WECHAT_URL_GET_MSG_IMG, cacheService.getHostUrl(), message.getMsgId(), cacheService.getsKey());
	        			String thumbImageUrl = fullImageUrl + "&type=slave";
	        			//群
	        			if (isMessageFromChatRoom(message)) {
	        				messageHandler.onReceivingChatRoomImageMessage(message, thumbImageUrl, fullImageUrl);
	        			}
	        			//个人
	        			else if (isMessageFromIndividual(message)) {
	        				messageHandler.onReceivingPrivateImageMessage(message, thumbImageUrl, fullImageUrl);
	        			}
	        		} 
	        		//表情
	        		else if (message.getMsgType() == MessageType.EMOTICON.getCode() || message.getAppMsgType() == AppMessageType.EMOJI.getCode()) {
	        			cacheService.getContactNamesWithUnreadMessage().add(message.getFromUserName());
	        			String emoticonUrl = String.format(WECHAT_URL_GET_MSG_IMG, cacheService.getHostUrl(), message.getMsgId(), cacheService.getsKey());
	        			emoticonUrl = emoticonUrl + "&type=big";
	        			//群
	        			if (isMessageFromChatRoom(message)) {
	        				messageHandler.onReceivingChatRoomEmoticonMessage(message, emoticonUrl);
	        			}
	        			//个人
	        			else if (isMessageFromIndividual(message)) {
	        				messageHandler.onReceivingPrivateEmoticonMessage(message, emoticonUrl);
	        			}
	        		}
	        		//语音消息
	        		else if (message.getMsgType() == MessageType.VOICE.getCode()) {
	        			cacheService.getContactNamesWithUnreadMessage().add(message.getFromUserName());
	        			String voiceUrl = String.format(WECHAT_URL_GET_VOICE, cacheService.getHostUrl(), message.getMsgId(), cacheService.getsKey());
	        			//群
	        			if (isMessageFromChatRoom(message)) {
	        				messageHandler.onReceivingChatRoomVoiceMessage(message, voiceUrl);
	        			}
	        			//个人
	        			else if (isMessageFromIndividual(message)) {
	        				messageHandler.onReceivingPrivateVoiceMessage(message, voiceUrl);
	        			}
	        		}
	        		//视频消息
	        		else if (message.getMsgType() == MessageType.VIDEO.getCode() || message.getMsgType() == MessageType.MICROVIDEO.getCode()) {
	        			cacheService.getContactNamesWithUnreadMessage().add(message.getFromUserName());
	        			String videoUrl = String.format(WECHAT_URL_GET_VIDEO, cacheService.getHostUrl(), message.getMsgId(), cacheService.getsKey());
	        			String thumbImageUrl = String.format(WECHAT_URL_GET_MSG_IMG, cacheService.getHostUrl(), message.getMsgId(), cacheService.getsKey()) + "&type=slave";
	        			//群
	        			if (isMessageFromChatRoom(message)) {
	        				messageHandler.onReceivingChatRoomVideoMessage(message, thumbImageUrl, videoUrl);
	        			}
	        			//个人
	        			else if (isMessageFromIndividual(message)) {
	        				messageHandler.onReceivingPrivateVideoMessage(message, thumbImageUrl, videoUrl);
	        			}
	        		}
	        		//多媒体(文件)消息
	        		else if (message.getMsgType() == MessageType.APP.getCode()) {
	        			cacheService.getContactNamesWithUnreadMessage().add(message.getFromUserName());
	        			String mediaUrl = String.format(WECHAT_URL_GET_MEDIA, cacheService.getFileUrl(), message.getFromUserName(), message.getMediaId(), escape(message.getFileName()), cacheService.getPassTicket());
	        			//群
	        			if (isMessageFromChatRoom(message)) {
	        				messageHandler.onReceivingChatRoomMediaMessage(message, mediaUrl);
	        			}
	        			//个人
	        			else if (isMessageFromIndividual(message)) {
	        				messageHandler.onReceivingPrivateMediaMessage(message, mediaUrl);
	        			}
	        		}
	        		//系统消息
	        		else if (message.getMsgType() == MessageType.SYS.getCode()) {
	        			//红包
	        			if (message.getAppMsgType() == AppMessageType.RED_ENVELOPES.getCode()) {
	        				logger.info("[*] you've received a red packet");
	        				String from = message.getFromUserName();
	        				Set<Contact> contacts = null;
	        				//群
	        				if (isMessageFromChatRoom(message)) {
	        					contacts = cacheService.getChatRooms();
	        				}
	        				//个人
	        				else if (isMessageFromIndividual(message)) {
	        					contacts = cacheService.getIndividuals();
	        				}
	        				if (contacts != null) {
	        					Contact contact = contacts.stream().filter(x -> Objects.equals(x.getUserName(), from)).findAny().orElse(null);
	        					messageHandler.onRedPacketReceived(contact);
	        				}
						}
	        		}
	        		//好友邀请
	        		else if (message.getMsgType() == MessageType.VERIFYMSG.getCode() && cacheService.getOwner().getUserName().equals(message.getToUserName())) {
	        			if (messageHandler.onReceivingFriendInvitation(message.getRecommendInfo())) {
	        				acceptFriendInvitation(message.getRecommendInfo());
	        				logger.info("[*] you've accepted the invitation");
	        				messageHandler.postAcceptFriendInvitation(message);
	        			} else {
	        				logger.info("[*] you've declined the invitation");
	        				//TODO decline invitation
	        			}
	        		}
	        		//状态同步消息
	        		else if (message.getMsgType() == MessageType.STATUSNOTIFY.getCode()) {
						// 消息已读
						if (message.getStatusNotifyCode() == StatusNotifyCode.READED.getCode()) {
							messageHandler.onStatusNotifyReaded(message);
						} 
						// 发起会话
						else if (message.getStatusNotifyCode() == StatusNotifyCode.ENTER_SESSION.getCode()) {
							messageHandler.onStatusNotifyEnterSession(message);
						} 
						//正在输入
						else if (message.getStatusNotifyCode() == StatusNotifyCode.INITED.getCode()) {
							messageHandler.onStatusNotifyInited(message);
						}
						// 同步通讯录
						else if (message.getStatusNotifyCode() == StatusNotifyCode.SYNC_CONV.getCode()) {
							Set<Contact> chatRooms = Arrays.stream(message.getStatusNotifyUserName().split(","))
									.filter(x -> x != null && x.startsWith("@@")).map(x -> {
										Contact contact = new Contact();
										contact.setUserName(x);
										return contact;
									}).collect(Collectors.toSet());
							
							long loop = 0;
							while (true) {
								ChatRoomDescription[] chatRoomDescriptions = chatRooms.stream()
									.skip(loop * 50)
									.limit(50)
									.map(x -> {
										ChatRoomDescription description = new ChatRoomDescription();
										description.setUserName(x.getUserName());
										return description;
									}).toArray(ChatRoomDescription[]::new);
								if (chatRoomDescriptions.length > 0) {
									BatchGetContactResponse batchGetContactResponse = wechatHttpService
											.batchGetContact(chatRoomDescriptions);
									WechatUtils.checkBaseResponse(batchGetContactResponse);
									logger.info("[*] batchGetContactResponse count = "
											+ batchGetContactResponse.getCount());
									batchGetContactResponse.getContactList().forEach(x -> {
										cacheService.getChatRooms().remove(x);
										cacheService.getChatRooms().add(x);
									});
								} else {
									break;
								}
								loop++;
							}
							messageHandler.onStatusNotifySyncConv(message);
						} 
						//关闭会话
						else if (message.getStatusNotifyCode() == StatusNotifyCode.QUIT_SESSION.getCode()) {
							messageHandler.onStatusNotifyQuitSession(message);
						}
	        		}
        		} catch (Exception e) {
        			logger.error("handler exception", e);
				}
        		
        	}
        }).start();
    }

    private void onContactsModified(Set<Contact> contacts) {
        Set<Contact> individuals = new HashSet<>();
        Set<Contact> chatRooms = new HashSet<>();
        Set<Contact> mediaPlatforms = new HashSet<>();

        for (Contact contact : contacts) {
            if (WechatUtils.isIndividual(contact)) {
                individuals.add(contact);
            } else if (WechatUtils.isMediaPlatform(contact)) {
                mediaPlatforms.add(contact);
            } else if (WechatUtils.isChatRoom(contact)) {
                chatRooms.add(contact);
            }
        }
        Map<String, String> seqMap = new HashMap<>();
        //individual
        if (individuals.size() > 0) {
            Set<Contact> existingIndividuals = cacheService.getIndividuals();
            Set<Contact> newIndividuals = individuals.stream().filter(x -> !existingIndividuals.contains(x)).collect(Collectors.toSet());
            individuals.forEach(x -> {
            	//更新seq唯一值
            	for (Contact c : existingIndividuals) {
					if(x.equals(c) && !x.getSeq().equals(c.getSeq())){
						seqMap.put(c.getSeq(), x.getSeq());
						break;
					}
				}
                existingIndividuals.remove(x);
                existingIndividuals.add(x);
            });
            if (messageHandler != null && newIndividuals.size() > 0) {
                messageHandler.onNewFriendsFound(newIndividuals);
            }
        }
        //chatroom
        if (chatRooms.size() > 0) {
            Set<Contact> existingChatRooms = cacheService.getChatRooms();
            Set<Contact> newChatRooms = new HashSet<>();
            Set<Contact> modifiedChatRooms = new HashSet<>();
            
            long loop = 0;
			while (true) {
				ChatRoomDescription[] chatRoomDescriptions = chatRooms.stream()
					.skip(loop * 50)
					.limit(50)
					.map(x -> {
						ChatRoomDescription description = new ChatRoomDescription();
						description.setUserName(x.getUserName());
						return description;
					}).toArray(ChatRoomDescription[]::new);
				if (chatRoomDescriptions.length > 0) {
					BatchGetContactResponse batchGetContactResponse = wechatHttpService
							.batchGetContact(chatRoomDescriptions);
					WechatUtils.checkBaseResponse(batchGetContactResponse);
					logger.info("[*] batchGetContactResponse count = " + batchGetContactResponse.getCount());
					batchGetContactResponse.getContactList().forEach(x -> {
		            	if (existingChatRooms.contains(x)) {
		            		//更新seq唯一值
			            	for (Contact c : existingChatRooms) {
								if(x.equals(c) && !x.getSeq().equals(c.getSeq())){
									seqMap.put(c.getSeq(), x.getSeq());
									break;
								}
							}
		                    modifiedChatRooms.add(x);
		                } else {
		                    newChatRooms.add(x);
		                }
					});
				} else {
					break;
				}
				loop++;
			}
			
//            for (Contact chatRoom : chatRooms) {
//                if (existingChatRooms.contains(chatRoom)) {
//                    modifiedChatRooms.add(chatRoom);
//                } else {
//                    newChatRooms.add(chatRoom);
//                }
//            }
            existingChatRooms.addAll(newChatRooms);
            if (messageHandler != null && newChatRooms.size() > 0) {
                messageHandler.onNewChatRoomsFound(newChatRooms);
            }
            for (Contact chatRoom : modifiedChatRooms) {
                Contact existingChatRoom = existingChatRooms.stream().filter(x -> x.getUserName().equals(chatRoom.getUserName())).findFirst().orElse(null);
                if (existingChatRoom == null) {
                    continue;
                }
                existingChatRooms.remove(existingChatRoom);
                existingChatRooms.add(chatRoom);
                if (messageHandler != null) {
                    Set<Contact> oldMembers = existingChatRoom.getMemberList();
                    Set<Contact> newMembers = chatRoom.getMemberList();
                    Set<Contact> joined = newMembers.stream().filter(x -> !oldMembers.contains(x)).collect(Collectors.toSet());
                    Set<Contact> left = oldMembers.stream().filter(x -> !newMembers.contains(x)).collect(Collectors.toSet());
                    if (joined.size() > 0 || left.size() > 0) {
                        messageHandler.onChatRoomMembersChanged(chatRoom, joined, left);
                    }
                    //获取新加入成员头像信息
                    if(joined.size() > 0){
                    	loop = 0;
            			while (true) {
            				ChatRoomDescription[] chatRoomDescriptions = joined.stream()
            					.skip(loop * 50)
            					.limit(50)
            					.map(x -> {
            						ChatRoomDescription description = new ChatRoomDescription();
            						description.setEncryChatRoomId(chatRoom.getEncryChatRoomId());
            						description.setUserName(x.getUserName());
            						return description;
            					}).toArray(ChatRoomDescription[]::new);
            				if (chatRoomDescriptions.length > 0) {
            					BatchGetContactResponse batchGetContactResponse = wechatHttpService
            							.batchGetContact(chatRoomDescriptions);
            					WechatUtils.checkBaseResponse(batchGetContactResponse);
            					logger.info("[*] batchGetContactResponse count = " + batchGetContactResponse.getCount());
            					batchGetContactResponse.getContactList().forEach(x -> {
            						chatRoom.getMemberList().remove(x);
            						chatRoom.getMemberList().add(x);
            					});
            				} else {
            					break;
            				}
            				loop++;
            			}
                    }
                }
            }
        }
        if (mediaPlatforms.size() > 0) {
            //media platform
            Set<Contact> existingPlatforms = cacheService.getMediaPlatforms();
            Set<Contact> newMediaPlatforms = existingPlatforms.stream().filter(x -> !existingPlatforms.contains(x)).collect(Collectors.toSet());
            mediaPlatforms.forEach(x -> {
                existingPlatforms.remove(x);
                existingPlatforms.add(x);
            });
            if (messageHandler != null && newMediaPlatforms.size() > 0) {
                messageHandler.onNewMediaPlatformsFound(newMediaPlatforms);
            }
        }
        
        if (messageHandler != null && seqMap.size() > 0) {
            messageHandler.onMembersSeqChanged(seqMap);
        }
    }

    private void onContactsDeleted(Set<Contact> contacts) {
        Set<Contact> individuals = new HashSet<>();
        Set<Contact> chatRooms = new HashSet<>();
        Set<Contact> mediaPlatforms = new HashSet<>();
        for (Contact contact : contacts) {
            if (WechatUtils.isIndividual(contact)) {
                individuals.add(contact);
                cacheService.getIndividuals().remove(contact);
            } else if (WechatUtils.isChatRoom(contact)) {
                chatRooms.add(contact);
                cacheService.getChatRooms().remove(contact);
            } else if (WechatUtils.isMediaPlatform(contact)) {
                mediaPlatforms.add(contact);
                cacheService.getMediaPlatforms().remove(contact);
            }
        }
        if (messageHandler != null) {
            if (individuals.size() > 0) {
                messageHandler.onFriendsDeleted(individuals);
            }
            if (chatRooms.size() > 0) {
                messageHandler.onChatRoomsDeleted(chatRooms);
            }
            if (mediaPlatforms.size() > 0) {
                messageHandler.onMediaPlatformsDeleted(mediaPlatforms);
            }
        }
    }
    
    private String escape(String str) throws IOException {
        return URLEncoder.encode(str, StandardCharsets.UTF_8.toString());
    }

	public MessageHandler getMessageHandler() {
		return messageHandler;
	}

	public void setMessageHandler(MessageHandler messageHandler) {
		this.messageHandler = messageHandler;
	}
    
}

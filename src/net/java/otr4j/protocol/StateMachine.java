package net.java.otr4j.protocol;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.util.LinkedList;
import java.util.Vector;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.interfaces.DHPublicKey;
import net.java.otr4j.message.*;
import net.java.otr4j.message.encoded.*;
import net.java.otr4j.message.unencoded.*;
import net.java.otr4j.protocol.crypto.CryptoConstants;
import net.java.otr4j.protocol.crypto.CryptoUtils;
import net.java.otr4j.protocol.crypto.DHKeyPairContainer;
import net.java.otr4j.protocol.crypto.DHPublicKeyContainer;
import net.java.otr4j.utils.Utils;

public final class StateMachine {

	public static String receivingMessage(OTR4jListener listener,
			UserState userState, String user, String account, String protocol,
			String msgText) throws NoSuchAlgorithmException,
			InvalidKeySpecException, InvalidKeyException,
			NoSuchPaddingException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException,
			NoSuchProviderException, SignatureException {

		ConnContext ctx = userState.getConnContext(user, account, protocol);
		int policy = listener.getPolicy(ctx);

		if (!PolicyUtils.getAllowV1(policy) && !PolicyUtils.getAllowV2(policy)) {
			return msgText;
		}

		if (msgText.startsWith(MessageHeader.QUERY1)
				|| msgText.startsWith(MessageHeader.QUERY2)) {
			receivingQueryMessage(ctx, listener, new QueryMessage(msgText));
			// User needs to know nothing about Query messages.
		} else if (msgText.startsWith(MessageHeader.DH_COMMIT)) {
			receivingDHCommitMessage(ctx, listener,
					new DHCommitMessage(msgText));
		} else if (msgText.startsWith(MessageHeader.DH_KEY)) {
			receivingDHKeyMessage(ctx, listener, new DHKeyMessage(msgText),
					account, protocol);
		} else if (msgText.startsWith(MessageHeader.REVEALSIG)) {
			throw new UnsupportedOperationException();
		} else if (msgText.startsWith(MessageHeader.SIGNATURE)) {
			throw new UnsupportedOperationException();
		} else if (msgText.startsWith(MessageHeader.V1_KEY_EXCHANGE)) {
			throw new UnsupportedOperationException();
		} else if (msgText.startsWith(MessageHeader.DATA1)
				|| msgText.startsWith(MessageHeader.DATA1)) {
			throw new UnsupportedOperationException();
		} else if (msgText.startsWith(MessageHeader.ERROR)) {
			receivingErrorMessage(ctx, listener, new ErrorMessage(msgText));
			// User needs to know nothing about Error messages.
		} else {
			PlainTextMessage plainTextMessage = new PlainTextMessage(msgText);
			return receivingPlainTextMessage(ctx, listener, plainTextMessage);
		}

		return msgText;
	}

	private static String receivingPlainTextMessage(ConnContext ctx,
			OTR4jListener listener, PlainTextMessage msg)
			throws InvalidKeyException, NoSuchAlgorithmException,
			NoSuchPaddingException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException,
			NoSuchProviderException {
		Vector<Integer> versions = msg.versions;
		int policy = listener.getPolicy(ctx);
		if (versions.size() < 1) {
			// plaintext without the whitespace tag
			switch (ctx.messageState) {
			case ENCRYPTED:
			case FINISHED:
				// Display the message to the user, but warn him that the
				// message was received unencrypted.
				listener.showWarning("The message was received unencrypted.");
				return msg.cleanText;
			case PLAINTEXT:
				// Simply display the message to the user. If REQUIRE_ENCRYPTION
				// is set, warn him that the message was received unencrypted.
				break;
			}
		} else {
			// plaintext with the whitespace tag
			String cleanText = msg.cleanText;
			switch (ctx.messageState) {
			case ENCRYPTED:
			case FINISHED:
				// Remove the whitespace tag and display the message to the
				// user,
				// but warn him that the message was received unencrypted.
				listener.showWarning("The message was received unencrypted.");
				return cleanText;
			case PLAINTEXT:
				// Remove the whitespace tag and display the message to the
				// user. If REQUIRE_ENCRYPTION is set, warn him that the message
				// was received unencrypted.
				if (PolicyUtils.getRequireEncryption(policy)) {
					listener
							.showWarning("The message was received unencrypted.");
				}
				return cleanText;
			}

			if (PolicyUtils.getWhiteSpaceStartsAKE(policy)) {
				// In any event, if WHITESPACE_START_AKE is set
				if (versions.contains(2) && PolicyUtils.getAllowV2(policy)) {
					// Start V2 AKE.
					startAuthV2(ctx, listener);
				} else if (versions.contains(1)
						&& PolicyUtils.getAllowV1(policy)) {
					// Send a Version 1 Key Exchange Message, and transition
					// authstate to AUTHSTATE_V1_SETUP.
					throw new UnsupportedOperationException();
				}
			}
		}

		return null;
	}

	private static void receivingQueryMessage(ConnContext ctx,
			OTR4jListener listener, QueryMessage msg)
			throws InvalidKeyException, NoSuchAlgorithmException,
			NoSuchPaddingException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException,
			NoSuchProviderException {
		Vector<Integer> versions = msg.versions;
		int policy = listener.getPolicy(ctx);
		if (versions.contains(2) && PolicyUtils.getAllowV2(policy)) {
			// Start V2 AKE.
			startAuthV2(ctx, listener);
		} else if (versions.contains(1) && PolicyUtils.getAllowV1(policy)) {
			throw new UnsupportedOperationException();
		}
	}

	private static void receivingErrorMessage(ConnContext ctx,
			OTR4jListener listener, ErrorMessage msg) {
		listener.showError(msg.error);
		int policy = listener.getPolicy(ctx);
		if (PolicyUtils.getErrorStartsAKE(policy)) {
			Vector<Integer> versions = new Vector<Integer>();
			if (PolicyUtils.getAllowV1(policy)) {
				versions.add(1);
			}
			if (PolicyUtils.getAllowV2(policy)) {
				versions.add(2);
			}
			QueryMessage queryMessage = new QueryMessage(versions);
			listener.injectMessage(queryMessage.toString());
		}
	}

	private static void receivingDHCommitMessage(ConnContext ctx,
			OTR4jListener listener, DHCommitMessage msg)
			throws NoSuchAlgorithmException,
			InvalidAlgorithmParameterException, NoSuchProviderException,
			InvalidKeyException, NoSuchPaddingException,
			IllegalBlockSizeException, BadPaddingException {
		if (!PolicyUtils.getAllowV2(listener.getPolicy(ctx))) {
			// If ALLOW_V2 is not set, ignore this message.
			return;
		}
		switch (ctx.authenticationState) {
		case NONE: {
			replyDHKeyMessage(ctx, listener);
			break;
		}
		case AWAITING_DHKEY: {
			BigInteger ourHash = new BigInteger(
					ctx.our_dh.getFirst().publicHash);
			BigInteger theirHash = new BigInteger(msg.gxHash);

			if (theirHash.abs().compareTo(ourHash.abs()) == -1) {
				// Ignore the incoming D-H Commit message, but resend your D-H
				// Commit message.
				DHCommitMessage dhCommit = new DHCommitMessage(2, ctx.r,
						(DHPublicKey) ctx.our_dh.getFirst().pair.getPublic(),
						ctx.our_dh.getFirst().publicHash);

				listener.injectMessage(dhCommit.toString());
			} else {
				replyDHKeyMessage(ctx, listener);
			}
			break;
		}
		case AWAITING_REVEALSIG: {
			// Retransmit your D-H Key Message (the same one as you sent when
			// you entered AUTHSTATE_AWAITING_REVEALSIG). Forget the old D-H
			// Commit message, and use this new one instead.
			DHKeyMessage dhKey = new DHKeyMessage(ctx.our_dh.size() - 1,
					(DHPublicKey) ctx.our_dh.getFirst().pair.getPublic());
			listener.injectMessage(dhKey.toString());
			ctx.authenticationState = AuthenticationState.AWAITING_REVEALSIG;
			break;
		}
		case AWAITING_SIG:
		case V1_SETUP: {
			// Reply with a new D-H Key message, and transition authstate to
			// AUTHSTATE_AWAITING_REVEALSIG.
			replyDHKeyMessage(ctx, listener);
			break;
		}
		}
	}

	private static void receivingDHKeyMessage(ConnContext ctx,
			OTR4jListener listener, DHKeyMessage msg, String account,
			String protocol) throws InvalidKeyException,
			NoSuchAlgorithmException, SignatureException,
			NoSuchPaddingException, InvalidAlgorithmParameterException,
			IllegalBlockSizeException, BadPaddingException {
		if (!PolicyUtils.getAllowV2(listener.getPolicy(ctx))) {
			// If ALLOW_V2 is not set, ignore this message.
			return;
		}

		switch (ctx.authenticationState) {
		case AWAITING_DHKEY:
			LinkedList<DHPublicKeyContainer> their_y = new LinkedList<DHPublicKeyContainer>();
			their_y.add(new DHPublicKeyContainer(msg.gy));
			replyRevealSignatureMessage(ctx, listener, account, protocol);
			break;
		case AWAITING_SIG:
			if (msg.gy.getEncoded().equals(ctx.their_y.getFirst())) {
				replyRevealSignatureMessage(ctx, listener, account, protocol);
			}
			break;
		default:
			break;
		}

	}

	private static void startAuthV2(ConnContext ctx, OTR4jListener listener)
			throws NoSuchAlgorithmException,
			InvalidAlgorithmParameterException, NoSuchProviderException,
			InvalidKeyException, NoSuchPaddingException,
			IllegalBlockSizeException, BadPaddingException {

		// Prepare DHCommit params
		byte[] r = Utils.getRandomBytes(CryptoConstants.AES_KEY_BYTE_LENGTH);
		DHKeyPairContainer container = new DHKeyPairContainer(CryptoUtils
				.generateDHKeyPair());

		// Try send the message.
		DHCommitMessage dhCommitMessage = new DHCommitMessage(2, r,
				(DHPublicKey) container.pair.getPublic(), container.publicHash);

		listener.injectMessage(dhCommitMessage.toString());

		// If everything is OK, update ConnContext
		ctx.r = r;
		LinkedList<DHKeyPairContainer> our_dh = new LinkedList<DHKeyPairContainer>();
		our_dh.add(container);
		our_dh.add(new DHKeyPairContainer(CryptoUtils.generateDHKeyPair()));
		ctx.our_dh = our_dh;
		ctx.authenticationState = AuthenticationState.AWAITING_DHKEY;
	}

	private static void replyDHKeyMessage(ConnContext ctx,
			OTR4jListener listener) throws NoSuchAlgorithmException,
			InvalidAlgorithmParameterException, NoSuchProviderException {

		// Prepare DHCommit params
		byte[] r = Utils.getRandomBytes(CryptoConstants.AES_KEY_BYTE_LENGTH);
		DHKeyPairContainer container = new DHKeyPairContainer(CryptoUtils
				.generateDHKeyPair());

		// Try send the message.
		DHKeyMessage dhKey = new DHKeyMessage(2, (DHPublicKey) container.pair
				.getPublic());
		listener.injectMessage(dhKey.toString());

		// If everything is OK, update ConnContext
		ctx.r = r;
		LinkedList<DHKeyPairContainer> our_dh = new LinkedList<DHKeyPairContainer>();
		our_dh.add(container);
		our_dh.add(new DHKeyPairContainer(CryptoUtils.generateDHKeyPair()));
		ctx.our_dh = our_dh;
		ctx.authenticationState = AuthenticationState.AWAITING_REVEALSIG;
	}

	private static void replyRevealSignatureMessage(ConnContext ctx,
			OTR4jListener listener, String account, String protocol)
			throws NoSuchAlgorithmException, InvalidKeyException,
			SignatureException, NoSuchPaddingException,
			InvalidAlgorithmParameterException, IllegalBlockSizeException,
			BadPaddingException {
		int protocolVersion = 2;

		// Get authentication keys.
		KeyPair pair = listener.createPrivateKey(account, protocol);
		PrivateKey privKey = pair.getPrivate();
		PublicKey pubKey = pair.getPublic();

		KeyPair our_pair = ctx.our_dh.getLast().pair;
		DHPublicKey gxKey = (DHPublicKey) our_pair.getPublic();
		DHPublicKey gyKey = ctx.their_y.getLast().key;
		int keyidB = ctx.our_dh.size() - 1;
		BigInteger s = CryptoUtils.generateSecret(our_pair);
		byte[] r = ctx.r;
		RevealSignatureMessage revealSignatureMessage = new RevealSignatureMessage(
				protocolVersion, s, gxKey, gyKey, keyidB, privKey, pubKey, r);
		listener.injectMessage(revealSignatureMessage.toString());
		ctx.authenticationState = AuthenticationState.AWAITING_SIG;
	}
}

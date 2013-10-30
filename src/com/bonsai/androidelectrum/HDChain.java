package com.bonsai.androidelectrum;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;

public class HDChain {

    private Logger mLogger;

    private NetworkParameters	mParams;
    private DeterministicKey	mChainKey;
    private boolean				mIsReceive;
    private String				mChainName;

    private ArrayList<DeterministicKey>		mAddrs;

    public HDChain(NetworkParameters params,
                   DeterministicKey accountKey,
                   boolean isReceive,
                   String chainName,
                   int numAddrs) {

        mLogger = LoggerFactory.getLogger(HDChain.class);

        mParams = params;
        int chainnum = isReceive ? 0 : 1;
        mChainKey = HDKeyDerivation.deriveChildKey(accountKey, chainnum);
        mIsReceive = isReceive;
        mChainName = chainName;

        mLogger.info("created HDChain " + mChainName + ": " +
                     mChainKey.getPath());

        mAddrs = new ArrayList<DeterministicKey>();
        for (int ii = 0; ii < numAddrs; ++ii) {
            DeterministicKey dk = HDKeyDerivation.deriveChildKey(mChainKey, ii);
            logAddress(dk);
            mAddrs.add(dk);
        }
    }

    public void addAllKeys(Wallet wallet) {
        for (DeterministicKey dk : mAddrs) {
            ECKey key = dk.toECKey();
            wallet.addKey(key);
        }
    }

    private void logAddress(DeterministicKey dk) {
        ECKey key = dk.toECKey();
        mLogger.info("created address " + dk.getPath() + ": " +
                     key.toAddress(mParams).toString() + " " +
                     key.getPrivateKeyEncoded(mParams).toString());
    }
}
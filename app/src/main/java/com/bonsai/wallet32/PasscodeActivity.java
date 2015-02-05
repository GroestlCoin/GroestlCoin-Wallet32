// Copyright (C) 2013-2014  Bonsai Software, Inc.
// 
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
// 
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
// 
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package com.bonsai.wallet32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.ActionBarActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import hashengineering.groestlcoin.wallet32.R;
public class PasscodeActivity extends ActionBarActivity {

    private static final int MAX_PASSCODE_LENGTH = 32;

    private static Logger mLogger =
        LoggerFactory.getLogger(PasscodeActivity.class);

    private WalletApplication mApp;

    private enum State {
        PASSCODE_CREATE,
        PASSCODE_CONFIRM,
        PASSCODE_ENTER
    }

    private enum Action {
        ACTION_CREATE,
        ACTION_RESTORE,
        ACTION_PAIR,
        ACTION_LOGIN,
        ACTION_CHANGE,
        ACTION_VIEWSEED,
        ACTION_SHOWPAIRING
    }

    private Resources mRes;
    SharedPreferences mPrefs;

    private boolean mChangePasscode;
    private Action mAction;

    private boolean	mShowPasscode;
    private State	mState;
    private String	mPasscode;
    private String	mLastPasscode;

    private WalletService mWalletService;

	private boolean mIsPaused = false;
	private boolean mPasscodeWasInvalid = false;

    protected ServiceConnection mConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className,
                                           IBinder binder) {
                mWalletService =
                    ((WalletService.WalletServiceBinder) binder).getService();
                mLogger.info("WalletService bound");
            }

            public void onServiceDisconnected(ComponentName className) {
                mWalletService = null;
                mLogger.info("WalletService unbound");
            }

    };

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_passcode);

        mApp = (WalletApplication) getApplicationContext();

        mRes = getResources();

        // If we haven't entered through the lobby at some point
        // we've gotten here via the "recent activities" menu or
        // similar.  Go to the lobby ...
        //
        if (!mApp.hasEntered())
        {
            mLogger.info("at passcode without entry; back to the lobby");

            // Go to the lobby and get logged in ...
            Intent intent = new Intent(this, LobbyActivity.class);
            startActivity(intent);
            finish();
        }

        Bundle bundle = getIntent().getExtras();

        String action = bundle.getString("action");
        if (action == null) {
            String msg = "missing action in PasscodeActivity";
            mLogger.error(msg);
            throw new RuntimeException(msg);
        } else if (action.equals("create")) {
            mAction = Action.ACTION_CREATE;
            mLogger.info("ACTION_CREATE");
        } else if (action.equals("restore")) {
            mAction = Action.ACTION_RESTORE;
            mLogger.info("ACTION_RESTORE");
        } else if (action.equals("pair")) {
            mAction = Action.ACTION_PAIR;
            mLogger.info("ACTION_PAIR");
        } else if (action.equals("login")) {
            mAction = Action.ACTION_LOGIN;
            mLogger.info("ACTION_LOGIN");
        } else if (action.equals("change")) {
            mAction = Action.ACTION_CHANGE;
            mLogger.info("ACTION_CHANGE");
        } else if (action.equals("viewseed")) {
            mAction = Action.ACTION_VIEWSEED;
            mLogger.info("ACTION_VIEWSEED");
        } else if (action.equals("showpairing")) {
            mAction = Action.ACTION_SHOWPAIRING;
            mLogger.info("ACTION_SHOWPAIRING");
        } else {
            String msg = "unknown action value " + action;
            mLogger.error(msg);
            throw new RuntimeException(msg);
        }

        TextView msgtv = (TextView) findViewById(R.id.message);

        switch (mAction) {
            // These actions verify the passcode.
        case ACTION_LOGIN:
        case ACTION_VIEWSEED:
        case ACTION_SHOWPAIRING:
            mState = State.PASSCODE_ENTER;
            mChangePasscode = false;
            msgtv.setText(R.string.passcode_enter);
            show_esthack(false);
            break;

            // These actions directly create a passcode.
        case ACTION_CREATE:
        case ACTION_RESTORE:
        case ACTION_PAIR:
            mState = State.PASSCODE_CREATE;
            mChangePasscode = false;
            msgtv.setText(R.string.passcode_create);
            show_esthack(true);
            break;

            // This action verifies the passcode and then creates a
            // new one.
        case ACTION_CHANGE:
            mState = State.PASSCODE_ENTER;
            mChangePasscode = true;
            msgtv.setText(R.string.passcode_current);
            show_esthack(false);
            break;
        }

        // Set the state of the show passcode checkbox.
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mShowPasscode = mPrefs.getBoolean("pref_showPasscode", false);
        CheckBox chkbx = (CheckBox) findViewById(R.id.show_passcode);
        chkbx.setChecked(mShowPasscode);
        chkbx.setOnCheckedChangeListener
            (new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView,
                                                 boolean isChecked) {
                        mShowPasscode = isChecked;
                        SharedPreferences.Editor editor = mPrefs.edit();
                        editor.putBoolean("pref_showPasscode", mShowPasscode);
                        editor.commit();
                        setPasscode(mPasscode);	// redisplay
                    }
                });

        setPasscode("");

        mLogger.info("PasscodeActivity created");
	}

    @SuppressLint("InlinedApi")
	@Override
    protected void onResume() {
        super.onResume();

        mLogger.info("PasscodeActivity resumed");

        mApp.cancelBackgroundTimeout();

        // NOTE - this passcode activity can happen on initial create
        // and login and the WalletService will not be started at that
        // time.  This is ok.
        //
        // We need a WalletService binding for the case where we change
        // the passcode and in this case it will be running ...
        //
        bindService(new Intent(this, WalletService.class), mConnection,
                    Context.BIND_ADJUST_WITH_ACTIVITY);

		mIsPaused = false;

		// Did we have an invalid passcode attempt complete while paused?
		if (mPasscodeWasInvalid) {
			mLogger.info("showing deferred passcode invalid dialog");
			mPasscodeWasInvalid = false;
			showPasscodeInvalidDialog();
		}
    }

    @Override
    protected void onPause() {
        mLogger.info("PasscodeActivity paused");

		mIsPaused = true;

        unbindService(mConnection);

        mApp.startBackgroundTimeout();

        super.onPause();
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.lobby_actions, menu);
        return super.onCreateOptionsMenu(menu);
	}

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle presses on the action bar items
        Intent intent;
        switch (item.getItemId()) {
        case R.id.action_about:
            intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    private void show_esthack(boolean show) {
        if (show) {
            findViewById(R.id.esthack_spacer).setVisibility(View.VISIBLE);
            findViewById(R.id.esthack_pair).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.esthack_spacer).setVisibility(View.GONE);
            findViewById(R.id.esthack_pair).setVisibility(View.GONE);
        }
    }

    public void enterDigit(View view) {
        // Is the passcode at maximum length?
        if (mPasscode.length() == MAX_PASSCODE_LENGTH) {
            String msg = mRes.getString(R.string.passcode_error_maxlen);
            mLogger.warn(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            return;
        }

        // Which button was clicked?
        String val;
        switch (view.getId()) {
        case R.id.button_1:	val = "1";		break;
        case R.id.button_2:	val = "2";		break;
        case R.id.button_3:	val = "3";		break;
        case R.id.button_4:	val = "4";		break;
        case R.id.button_5:	val = "5";		break;
        case R.id.button_6:	val = "6";		break;
        case R.id.button_7:	val = "7";		break;
        case R.id.button_8:	val = "8";		break;
        case R.id.button_9:	val = "9";		break;
        case R.id.button_0:	val = "0";		break;
        default:			val = "?";		break;
        }

        // Update the textview.
        setPasscode(mPasscode + val);
    }

    public void deleteDigit(View view) {
        int len = mPasscode.length();
        if (len == 0)
            return;		// Nothing to do here.
        else
            setPasscode(mPasscode.substring(0, len - 1));	// Strip last.
    }

    public void clearPasscode(View view) {
        setPasscode("");	// Clear the string.
    }

    public void submitPasscode(View view) {
        // We don't currently allow empty passcodes.
        // If we do, we'll have to side-step the keyCrypter.deriveKey
        // step because it doesn't like empty passcodes ...
        if (mPasscode.length() == 0) {
            showErrorDialog(mRes.getString(R.string.passcode_errortitle),
                            mRes.getString(R.string.passcode_empty));
            return;
        }

        switch (mState) {
        case PASSCODE_CREATE:	confirmPasscode();		break;
        case PASSCODE_CONFIRM:	checkPasscode();		break;
        case PASSCODE_ENTER:	validatePasscode();		break;
        }
    }

    // We're creating a passcode and it's been entered once.
    private void confirmPasscode() {
        // Stash the first version of the passcode.
        mLastPasscode = mPasscode;

        // Clear the passcode field.
        setPasscode("");	// Clear the string.

        // Ask the user to confirm it.
        TextView msgtv = (TextView) findViewById(R.id.message);
        msgtv.setText(R.string.passcode_confirm);

        mState = State.PASSCODE_CONFIRM;
    }

    // We're creating a passcode and it's been entered a second time.
    private void checkPasscode() {
        // Do they match?
        if (mPasscode.equals(mLastPasscode)) {
            // They matched ... setup async
            new SetupPasscodeTask().execute(mPasscode);
        }

        else {
            // Didn't match, try again ...

            showErrorDialog(mRes.getString(R.string.passcode_errortitle),
                            mRes.getString(R.string.passcode_mismatch));
            // Clear the passcode.
            setPasscode("");	// Clear the string.

            // Ask the user to create again.
            TextView msgtv = (TextView) findViewById(R.id.message);
            msgtv.setText(R.string.passcode_create);

            mState = State.PASSCODE_CREATE;
        }
    }

    private void setupComplete() {

        Intent intent;

        // In all cases we are effectively logged in now.
        mApp.setLoggedIn();

        switch (mAction) {
        case ACTION_CREATE:
            // Create the wallet.
            WalletUtil.createWallet(getApplicationContext());

            // Spin up the WalletService.
            Intent svcintent = new Intent(this, WalletService.class);
            Bundle bundle = new Bundle();
            bundle.putString("SyncState", "CREATED");
            svcintent.putExtras(bundle);
            startService(svcintent);

            intent = new Intent(this, ViewSeedActivity.class);
            Bundle bundle2 = new Bundle();
            bundle2.putBoolean("showDone", true);
            intent.putExtras(bundle2);
            startActivity(intent);
            break;

        case ACTION_RESTORE:
            intent = new Intent(this, RestoreWalletActivity.class);
            startActivity(intent);
            break;

        case ACTION_PAIR:
            intent = new Intent(this, PairWalletActivity.class);
            startActivity(intent);
            break;

        case ACTION_CHANGE:
            // We're all set, back to where we came from.
            break;

        default:
            // Shouldn't ever get here.
            String msg = "unexpected action in setupComplete";
            mLogger.error(msg);
            throw new RuntimeException(msg);
        }

        // And we're done here ...
        finish();
    }

    // We need to validate the passcode.
    private void validatePasscode() {
        new ValidatePasscodeTask().execute(mPasscode);
    }

	private void showPasscodeInvalidDialog() {
		showErrorDialog(mRes.getString(R.string.passcode_errortitle),
						mRes.getString(R.string.passcode_invalid));

		// Ask the user to create again.
		TextView msgtv = (TextView) findViewById(R.id.message);
		msgtv.setText(R.string.passcode_enter);
	}

    private void validateComplete(boolean isValid) {

        if (!isValid) {
			mLogger.info("passcode invalid");

            // Clear the passcode.
            setPasscode("");	// Clear the string.

            mState = State.PASSCODE_ENTER;

			// If we are paused we defer the dialog to when we
			// are resumed ...
			//
			if (!mIsPaused) {
				showPasscodeInvalidDialog();
			}
			else {
				mLogger.info("deferring passcode invalid dialog");
				mPasscodeWasInvalid = true;
			}

            return;
        }

        // The passcode was valid.
        mApp.setPasscodeValidTimestamp();

        switch (mAction) {
        case ACTION_LOGIN:
            // Spin up the WalletService.
            Intent svcintent = new Intent(this, WalletService.class);
            Bundle bundle = new Bundle();
            bundle.putString("SyncState", "STARTUP");
            svcintent.putExtras(bundle);
            startService(svcintent);

            mApp.setLoggedIn();

            // Off to the main activity.
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);

            // And we're done with this activity.
            finish();
            break;

        case ACTION_CHANGE:
            // Now we're ready to create a new passcode.
            mState = State.PASSCODE_CREATE;
            TextView msgtv = (TextView) findViewById(R.id.message);
            msgtv.setText(R.string.passcode_create);
            setPasscode("");
            show_esthack(true);
            return;

        case ACTION_VIEWSEED:
            // Off to view the seed.
            Intent intent2 = new Intent(this, ViewSeedActivity.class);
            intent2.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent2);

            // And we're done with this activity.
            finish();
            break;

        case ACTION_SHOWPAIRING:
            // Off to view the pairing code.
            Intent intent3 = new Intent(this, ShowPairingActivity.class);
            intent3.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent3);

            // And we're done with this activity.
            finish();
            break;
        }
    }

    // Set the passcode, add decorations, optionally hide values.
    private void setPasscode(String val) {
        mPasscode = val;
        StringBuilder bldr = new StringBuilder();
        int len = val.length();
        for (int ii = 0; ii < len; ii += 4) {
            if (ii != 0)
                bldr.append("-");
            int end = (ii + 4 > len) ? len : ii + 4;
            if (mShowPasscode) {
                bldr.append(val.substring(ii, end));
            }
            else {
                for (int jj = ii; jj < end; ++jj)
                    bldr.append("*");
            }
        }
        TextView pctv = (TextView) findViewById(R.id.passcode);
        pctv.setText(bldr.toString());

        // Update the estimated hack cost.
        String esthackstr = "$" + String.format("%.2f", esthack(len));
        TextView ehtv = (TextView) findViewById(R.id.esthack_value);
        ehtv.setText(esthackstr);
    }

    private double esthack(int len) {
        // (1*10^len) * C / R

        double nscrypt = Math.pow(10.0, len);
        double cost_per_host_second = 2.314e-8;
        double scrypt_per_host_second = 20;

        return nscrypt * cost_per_host_second / scrypt_per_host_second;
    }

    public static class MyDialogFragment extends DialogFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            super.onCreateDialog(savedInstanceState);
            String msg = getArguments().getString("msg");
            String title = getArguments().getString("title");
            boolean hasOK = getArguments().getBoolean("hasOK");
            AlertDialog.Builder builder =
                new AlertDialog.Builder(getActivity());
            builder.setTitle(title);
            builder.setMessage(msg);
            if (hasOK) {
                builder
                    .setPositiveButton(R.string.base_error_ok,
                                       new DialogInterface.OnClickListener() {
                                           public void onClick(DialogInterface di,
                                                               int id) {
                                              }
                                          });
            }
            return builder.create();
        }
    }

    protected DialogFragment showErrorDialog(String title, String msg) {
        DialogFragment df = new MyDialogFragment();
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("msg", msg);
        args.putBoolean("hasOK", true);
        df.setArguments(args);
        df.show(getSupportFragmentManager(), "error");
        return df;
    }

    protected DialogFragment showModalDialog(String title, String msg) {
        DialogFragment df = new MyDialogFragment();
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("msg", msg);
        args.putBoolean("hasOK", false);
        df.setArguments(args);
        df.setCancelable(false);
        df.show(getSupportFragmentManager(), "note");
        return df;
    }

    private class SetupPasscodeTask extends AsyncTask<String, Void, Void> {
        DialogFragment df;

        @Override
        protected void onPreExecute() {
            String msg = mAction == Action.ACTION_CHANGE ?
                mRes.getString(R.string.passcode_waitchange) :
                mRes.getString(R.string.passcode_waitsetup);
            df = showModalDialog(mRes.getString(R.string.passcode_waittitle),
                                 msg);
        }

		protected Void doInBackground(String... arg0)
        {
            String passcode = arg0[0];
            // This takes a while (scrypt) ...
            WalletUtil.setPasscode(getApplicationContext(), mWalletService,
                                   passcode, mChangePasscode);
			return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            df.dismissAllowingStateLoss();
            setupComplete();
        }
    }

    private class ValidatePasscodeTask
        extends AsyncTask<String, Void, Boolean> {

        DialogFragment df;

        @Override
        protected void onPreExecute() {
            String msg = mAction == Action.ACTION_LOGIN ?
                mRes.getString(R.string.passcode_waitdecrypt) :
                mRes.getString(R.string.passcode_waitvalidate);
            df = showModalDialog(mRes.getString(R.string.passcode_waittitle),
                                 msg);
        }

		protected Boolean doInBackground(String... arg0)
        {
            String passcode = arg0[0];
            // This takes a while (scrypt) ...
            return WalletUtil.passcodeValid(getApplicationContext(), passcode);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            df.dismissAllowingStateLoss();
            validateComplete(result.booleanValue());
        }
    }
}

// Local Variables:
// mode: java
// c-basic-offset: 4
// tab-width: 4
// End:

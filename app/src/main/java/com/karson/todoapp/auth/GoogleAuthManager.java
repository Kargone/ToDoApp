package com.karson.todoapp.auth;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.karson.todoapp.R;

import java.util.concurrent.Executor;

/**
 * Google Sign-In via Credential Manager, exchanged for a Firebase Auth session — the Android
 * equivalent of the web app's {@code signInWithPopup(auth, GoogleAuthProvider)} flow. The web
 * client ID is read from the {@code default_web_client_id} string resource that the
 * google-services plugin generates from {@code google-services.json}, so nothing is hardcoded.
 */
public class GoogleAuthManager {
    private static final String TAG = "GoogleAuthManager";

    private final FirebaseAuth auth = FirebaseAuth.getInstance();

    public boolean isSignedIn() {
        return auth.getCurrentUser() != null;
    }

    @Nullable
    public FirebaseUser currentUser() {
        return auth.getCurrentUser();
    }

    /** Launches the Google sign-in UI and, on success, signs the user into Firebase. */
    public void signIn(@NonNull Activity activity, @NonNull SignInCallback callback) {
        String serverClientId = activity.getString(R.string.default_web_client_id);
        GetSignInWithGoogleOption option =
                new GetSignInWithGoogleOption.Builder(serverClientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        CredentialManager credentialManager = CredentialManager.create(activity);
        Executor mainExecutor = ContextCompat.getMainExecutor(activity);
        credentialManager.getCredentialAsync(activity, request, null, mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleCredential(activity, result, callback);
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            // User backed out — not an error worth surfacing.
                            callback.onError(null);
                            return;
                        }
                        Log.w(TAG, "Credential retrieval failed", e);
                        callback.onError(e instanceof NoCredentialException
                                ? "No Google account available to sign in."
                                : "Sign-in failed. Please try again.");
                    }
                });
    }

    private void handleCredential(@NonNull Activity activity, GetCredentialResponse result,
                                  @NonNull SignInCallback callback) {
        Credential credential = result.getCredential();
        String type = credential.getType();
        boolean isGoogleToken = credential instanceof CustomCredential
                && (GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(type)
                || GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL.equals(type));
        if (!isGoogleToken) {
            Log.w(TAG, "Unexpected credential type: " + type);
            callback.onError("Unexpected sign-in response.");
            return;
        }
        try {
            GoogleIdTokenCredential googleCredential =
                    GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
            firebaseSignIn(activity, googleCredential.getIdToken(), callback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Google ID token", e);
            callback.onError("Could not read Google credentials.");
        }
    }

    private void firebaseSignIn(@NonNull Activity activity, String idToken,
                                @NonNull SignInCallback callback) {
        AuthCredential firebaseCredential = GoogleAuthProvider.getCredential(idToken, null);
        auth.signInWithCredential(firebaseCredential)
                .addOnCompleteListener(activity, task -> {
                    if (task.isSuccessful()) {
                        callback.onSuccess(auth.getCurrentUser());
                    } else {
                        Log.w(TAG, "Firebase sign-in failed", task.getException());
                        callback.onError("Firebase sign-in failed.");
                    }
                });
    }

    /** Signs out of Firebase and clears the saved Google credential state. */
    public void signOut(@NonNull Context context, @Nullable Runnable onDone) {
        auth.signOut();
        CredentialManager credentialManager = CredentialManager.create(context);
        Executor mainExecutor = ContextCompat.getMainExecutor(context);
        credentialManager.clearCredentialStateAsync(new ClearCredentialStateRequest(), null,
                mainExecutor, new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void result) {
                        if (onDone != null) onDone.run();
                    }

                    @Override
                    public void onError(@NonNull ClearCredentialException e) {
                        // Firebase is already signed out; proceed regardless.
                        Log.w(TAG, "clearCredentialState failed", e);
                        if (onDone != null) onDone.run();
                    }
                });
    }

    public interface SignInCallback {
        void onSuccess(@Nullable FirebaseUser user);

        /** {@code message} is null when the user simply cancelled — show nothing in that case. */
        void onError(@Nullable String message);
    }
}

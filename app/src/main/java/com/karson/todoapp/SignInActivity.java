package com.karson.todoapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.karson.todoapp.auth.GoogleAuthManager;

/**
 * Launcher screen and auth gate. If a Firebase session already exists it jumps straight to
 * {@link MainActivity}; otherwise it shows a single "Sign in with Google" button. Mirrors the
 * web app's signInShell / appShell split driven by auth state.
 */
public class SignInActivity extends AppCompatActivity {

    private GoogleAuthManager authManager;
    private View signInButton;
    private View progress;
    private boolean navigating;

    /** Fires whenever Firebase auth state changes; a non-null user means we're in — go to the app.
     *  This catches a sign-in even if the per-call callback was lost to an activity recreation. */
    private final FirebaseAuth.AuthStateListener authStateListener = firebaseAuth -> {
        if (firebaseAuth.getCurrentUser() != null) goToApp();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MainActivity.applyStoredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);

        View root = findViewById(R.id.sign_in_root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });

        authManager = new GoogleAuthManager();
        signInButton = findViewById(R.id.google_sign_in_button);
        progress = findViewById(R.id.sign_in_progress);
        signInButton.setOnClickListener(v -> startSignIn());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Fires immediately with the current state (covers a returning user) and again if the sign-in
        // completes while this screen is in the foreground. Removed in onStop to avoid leaks.
        authManager.addAuthStateListener(authStateListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        authManager.removeAuthStateListener(authStateListener);
    }

    private void startSignIn() {
        setBusy(true);
        authManager.signIn(this, new GoogleAuthManager.SignInCallback() {
            @Override
            public void onSuccess(com.google.firebase.auth.FirebaseUser user) {
                goToApp(); // the auth-state listener usually gets here first; goToApp is idempotent
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                if (message != null) {
                    Toast.makeText(SignInActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void setBusy(boolean busy) {
        signInButton.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
    }

    private void goToApp() {
        if (navigating) return; // guard against the listener and the callback both firing
        navigating = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}

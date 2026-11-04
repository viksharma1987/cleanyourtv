package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * Full-screen translucent overlay showing running user apps as horizontal cards,
 * with a bottom round 'X' button to kill all background apps and free RAM.
 */
final class TasksOverlayDialog {
    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PopupWindow popup;
    private final View root;
    private final HorizontalScrollView scrollView;
    private final LinearLayout cardsContainer;
    private final TextView emptyMessage;
    private final TextView ramStatus;
    private final TextView feedbackMessage;
    private final ImageButton btnKillAll;
    private final Runnable onDismissCallback;

    TasksOverlayDialog(Activity activity, Runnable onDismissCallback) {
        this.activity = activity;
        this.onDismissCallback = onDismissCallback;

        LayoutInflater inflater = LayoutInflater.from(activity);
        root = inflater.inflate(R.layout.dialog_tasks_overlay, null);

        scrollView = root.findViewById(R.id.cardsScrollView);
        cardsContainer = root.findViewById(R.id.cardsContainer);
        emptyMessage = root.findViewById(R.id.emptyMessage);
        ramStatus = root.findViewById(R.id.ramStatus);
        feedbackMessage = root.findViewById(R.id.feedbackMessage);
        btnKillAll = root.findViewById(R.id.btnKillAll);

        popup = new PopupWindow(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(android.R.style.Animation_Dialog);

        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dismiss();
                return true;
            }
            return false;
        });

        popup.setOnDismissListener(() -> {
            if (onDismissCallback != null) onDismissCallback.run();
        });

        btnKillAll.setOnClickListener(v -> killAllApps());

        // Focus navigation between cards and the round kill button
        btnKillAll.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dismiss();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (cardsContainer.getChildCount() > 0) {
                    cardsContainer.getChildAt(0).requestFocus();
                    return true;
                }
            }
            return false;
        });
    }

    void show(View parent) {
        updateRamSummary();
        cardsContainer.removeAllViews();
        emptyMessage.setVisibility(View.GONE);
        feedbackMessage.setVisibility(View.GONE);

        popup.showAtLocation(parent, Gravity.CENTER, 0, 0);

        // Load running apps in background
        Executors.newSingleThreadExecutor().execute(() -> {
            List<MemoryGuard.AppCard> apps = MemoryGuard.getRunningUserApps(activity);
            mainHandler.post(() -> populateCards(apps));
        });
    }

    private void updateRamSummary() {
        String ram = MemoryGuard.getRamSummary(activity);
        ramStatus.setText(ram);
    }

    private void populateCards(List<MemoryGuard.AppCard> apps) {
        cardsContainer.removeAllViews();
        if (apps.isEmpty()) {
            emptyMessage.setVisibility(View.VISIBLE);
            btnKillAll.requestFocus();
            return;
        }

        emptyMessage.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(activity);

        for (int i = 0; i < apps.size(); i++) {
            MemoryGuard.AppCard cardData = apps.get(i);
            View cardView = inflater.inflate(R.layout.item_task_card, cardsContainer, false);

            ImageView iconView = cardView.findViewById(R.id.cardIcon);
            TextView labelView = cardView.findViewById(R.id.cardLabel);

            iconView.setImageDrawable(cardData.icon);
            labelView.setText(cardData.label);

            cardView.setOnFocusChangeListener((v, hasFocus) -> {
                float scale = hasFocus ? 1.08f : 1.0f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
                if (hasFocus) {
                    // Center the focused card in the horizontal scroll view
                    int scrollX = (v.getLeft() + v.getRight() - scrollView.getWidth()) / 2;
                    scrollView.smoothScrollTo(Math.max(0, scrollX), 0);
                }
            });

            cardView.setOnClickListener(v -> launchApp(cardData.packageName));

            // Up D-pad: Dismiss / Kill this single card with slide-up animation
            // Down D-pad: navigate to the round kill button
            cardView.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dismiss();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
                    dismissSingleCard(cardView, cardData.packageName);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                    btnKillAll.requestFocus();
                    return true;
                }
                return false;
            });

            cardsContainer.addView(cardView);
        }

        // Request focus on first card
        if (cardsContainer.getChildCount() > 0) {
            cardsContainer.getChildAt(0).requestFocus();
        }
    }

    private void launchApp(String packageName) {
        PackageManager pm = activity.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(packageName);
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                MemoryGuard.recordAppLaunched(packageName);
                activity.startActivity(intent);
                dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    private void dismissSingleCard(View cardView, String packageName) {
        int index = cardsContainer.indexOfChild(cardView);
        if (index < 0) return;

        // Slide up and fade out lightweight destroying animation
        cardView.animate()
                .translationY(-220f)
                .alpha(0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(180)
                .withEndAction(() -> {
                    MemoryGuard.killPackage(activity, packageName);

                    int removeIdx = cardsContainer.indexOfChild(cardView);
                    if (removeIdx >= 0) {
                        cardsContainer.removeViewAt(removeIdx);
                    }

                    updateRamSummary();

                    int count = cardsContainer.getChildCount();
                    if (count > 0) {
                        int targetIdx = Math.min(removeIdx, count - 1);
                        View nextFocus = cardsContainer.getChildAt(targetIdx);
                        if (nextFocus != null) nextFocus.requestFocus();
                    } else {
                        emptyMessage.setVisibility(View.VISIBLE);
                        btnKillAll.requestFocus();
                    }
                }).start();
    }

    private void killAllApps() {
        int killed = MemoryGuard.killAllBackgroundUserApps(activity);
        cardsContainer.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            cardsContainer.removeAllViews();
            cardsContainer.setAlpha(1f);
            emptyMessage.setVisibility(View.VISIBLE);
            updateRamSummary();
            feedbackMessage.setText(activity.getString(R.string.memory_freed));
            feedbackMessage.setVisibility(View.VISIBLE);
            // Auto dismiss overlay after showing successful feedback
            mainHandler.postDelayed(this::dismiss, 800);
        }).start();
    }

    void dismiss() {
        mainHandler.removeCallbacksAndMessages(null);
        if (popup.isShowing()) {
            popup.dismiss();
        }
    }
}


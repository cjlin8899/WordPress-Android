package org.wordpress.android.ui.comments;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.NetworkImageView;
import com.wordpress.rest.RestRequest;

import org.json.JSONObject;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.models.Comment;
import org.wordpress.android.models.CommentStatus;
import org.wordpress.android.models.Note;
import org.wordpress.android.ui.notifications.NotificationFragment;
import org.wordpress.android.util.EditTextUtils;
import org.wordpress.android.util.Emoticons;
import org.wordpress.android.util.GravatarUtils;
import org.wordpress.android.util.JSONUtil;
import org.wordpress.android.util.MessageBarUtils;
import org.wordpress.android.util.ReaderAniUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.WPHtmlTagHandler;
import org.wordpress.android.util.WPImageGetter;

import java.net.URI;
import java.util.Map;

/**
 * Created by nbradbury on 11/11/13.
 * comment detail displayed from both the notification list and the comment list
 * prior to this there were separate comment detail screens for each list
 */
public class CommentDetailFragment extends Fragment implements NotificationFragment {
    private int mAccountId;

    private Comment mComment;
    private Note mNote;

    private boolean mIsReplyBoxShowing = false;
    private boolean mIsSubmittingReply = false;
    private boolean mIsApprovingComment = false;
    private boolean mIsRequestingComment = false;

    private OnCommentChangeListener mChangeListener;

    public interface OnCommentChangeListener {
        public void onCommentModerated(Comment comment);
        public void onCommentAdded();
    }

    /*
     * used when called from comment list
     */
    protected static CommentDetailFragment newInstance(int blogId, final Comment comment) {
        CommentDetailFragment fragment = new CommentDetailFragment();
        fragment.setBlogId(blogId);
        fragment.setComment(comment);
        return fragment;
    }

    /*
     * used when called from notification list for a comment notification
     */
    public static CommentDetailFragment newInstance(final Note note) {
        CommentDetailFragment fragment = new CommentDetailFragment();
        fragment.setNote(note);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.comment_detail_fragment, container, false);
        return view;
    }

    private void setBlogId(int blogId) {
        //mBlogId = blogId;
        mAccountId = WordPress.wpDB.getAccountIdForBlogId(blogId);
    }

    protected void setComment(final Comment comment) {
        mComment = comment;
        if (hasActivity())
            showComment();
    }

    @Override
    public Note getNote() {
        return mNote;
    }

    @Override
    public void setNote(Note note) {
        mNote = note;
        if (hasActivity())
            showComment();
    }

    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mChangeListener = (OnCommentChangeListener) activity;
        } catch (ClassCastException e) {
            mChangeListener = null;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        showComment();
    }

    /*
     * enable replying to this comment
     */
    private void showReplyBox() {
        if (mIsReplyBoxShowing || !hasActivity())
            return;

        final ViewGroup layoutComment = (ViewGroup) getActivity().findViewById(R.id.layout_comment_box);
        final EditText editReply = (EditText) layoutComment.findViewById(R.id.edit_comment);
        final ImageView imgSubmit = (ImageView) getActivity().findViewById(R.id.image_post_comment);

        editReply.setHint(R.string.reply_to_comment);
        editReply.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId==EditorInfo.IME_ACTION_DONE || actionId==EditorInfo.IME_ACTION_SEND)
                    submitReply();
                return false;
            }
        });

        imgSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitReply();
            }
        });

        ReaderAniUtils.flyIn(layoutComment);
        mIsReplyBoxShowing = true;
    }

    private void hideReplyBox(boolean hideImmediately) {
        if (!mIsReplyBoxShowing || !hasActivity())
            return;

        final ViewGroup layoutComment = (ViewGroup) getActivity().findViewById(R.id.layout_comment_box);
        if (hideImmediately) {
            layoutComment.setVisibility(View.GONE);
        } else {
            ReaderAniUtils.flyOut(layoutComment);
        }
        mIsReplyBoxShowing = false;

    }

    private boolean hasActivity() {
        return (getActivity() != null);
    }

    private boolean hasComment() {
        return (mComment != null);
    }

    /*
     * display the current comment
     */
    private void showComment() {
        if (!hasActivity())
            return;

        final NetworkImageView imgAvatar = (NetworkImageView) getActivity().findViewById(R.id.image_avatar);
        final TextView txtName = (TextView) getActivity().findViewById(R.id.text_name);
        final TextView txtDate = (TextView) getActivity().findViewById(R.id.text_date);
        final TextView txtContent = (TextView) getActivity().findViewById(R.id.text_content);
        final TextView txtBtnApprove = (TextView) getActivity().findViewById(R.id.text_approve);

        // clear all views when comment is null
        if (mComment == null) {
            imgAvatar.setImageDrawable(null);
            txtName.setText(null);
            txtDate.setText(null);
            txtContent.setText(null);
            txtBtnApprove.setVisibility(View.GONE);

            // if a notification was passed, request its associated comment
            if (mNote != null && !mIsRequestingComment)
                showCommentForNote(mNote);

            return;
        }

        txtName.setText(TextUtils.isEmpty(mComment.name) ? getString(R.string.anonymous) : StringUtils.unescapeHTML(mComment.name));
        txtDate.setText(mComment.dateCreatedFormatted);

        // convert emoticons first so their images won't be downloaded, then convert to HTML with an
        // image getter that enforces a max image size
        String content = StringUtils.notNullStr(mComment.comment);
        if (content.contains("icon_"))
            content = Emoticons.replaceEmoticonsWithEmoji((SpannableStringBuilder) Html.fromHtml(content)).toString().trim();
        final SpannableStringBuilder html;
        if (content.contains("<img")) {
            int maxImageSz = getResources().getDimensionPixelSize(R.dimen.reader_comment_max_image_size);
            html = (SpannableStringBuilder) Html.fromHtml(content, new WPImageGetter(getActivity(), txtContent, maxImageSz), null);
        } else {
            html = (SpannableStringBuilder) Html.fromHtml(content);
        }
        txtContent.setText(html);


        int avatarSz = getResources().getDimensionPixelSize(R.dimen.reader_avatar_sz_large);
        imgAvatar.setDefaultImageResId(R.drawable.placeholder);
        if (mComment.profileImageUrl == null) {
            if (!TextUtils.isEmpty(mComment.authorEmail)) {
                String avatarUrl = GravatarUtils.gravatarUrlFromEmail(mComment.authorEmail, avatarSz);
                imgAvatar.setImageUrl(avatarUrl, WordPress.imageLoader);
            } else {
                imgAvatar.setImageResource(R.drawable.placeholder);
            }
        } else {
            imgAvatar.setImageUrl(mComment.profileImageUrl.toString(), WordPress.imageLoader);
        }

        // approve button only appears when comment hasn't already been approved,
        // reply box only appears for approved comments
        if (mComment.getStatusEnum() == CommentStatus.APPROVED) {
            txtBtnApprove.setVisibility(View.GONE);
            showReplyBox();
        } else {
            txtBtnApprove.setVisibility(View.VISIBLE);
            txtBtnApprove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    approveComment();
                }
            });
            hideReplyBox(true);
        }
    }

    /*
     * approve the current comment
     */
    private void approveComment() {
        if (!hasActivity() || !hasComment() || mIsApprovingComment)
            return;

        final TextView txtBtnApprove = (TextView) getActivity().findViewById(R.id.text_approve);
        ReaderAniUtils.flyOut(txtBtnApprove);

        // immediately show MessageBox saying comment has been approved - runnable below executes
        // once MessageBar disappears
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                showReplyBox();
            }
        };
        MessageBarUtils.showMessageBar(getActivity(),
                                       getString(R.string.comment_approved),
                                       MessageBarUtils.MessageBarType.INFO,
                                       runnable);

        mIsApprovingComment = true;

        CommentActions.CommentActionListener actionListener = new CommentActions.CommentActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                mIsApprovingComment = false;
                if (hasActivity()) {
                    if (succeeded) {
                        mComment.setStatus(CommentStatus.toString(CommentStatus.APPROVED, CommentStatus.ApiFormat.XMLRPC));
                        if (mChangeListener != null)
                            mChangeListener.onCommentModerated(mComment);
                    } else {
                        hideReplyBox(false);
                        txtBtnApprove.setVisibility(View.VISIBLE);
                        ToastUtils.showToast(getActivity(), R.string.error_moderate_comment, ToastUtils.Duration.LONG);
                    }
                }
            }
        };
        CommentActions.moderateComment(WordPress.currentBlog, mComment, CommentStatus.APPROVED, actionListener);
    }

    /*
     * post comment box text as a reply to the current comment
     */
    private void submitReply() {
        if (!hasActivity() || mIsSubmittingReply)
            return;

        final EditText editComment = (EditText) getActivity().findViewById(R.id.edit_comment);
        final ProgressBar progress = (ProgressBar) getActivity().findViewById(R.id.progress_submit_comment);
        final ImageView imgSubmit = (ImageView) getActivity().findViewById(R.id.image_post_comment);

        final String replyText = EditTextUtils.getText(editComment);
        if (TextUtils.isEmpty(replyText))
            return;

        // disable editor, hide soft keyboard, hide submit icon, and show progress spinner while submitting
        editComment.setEnabled(false);
        EditTextUtils.hideSoftInput(editComment);
        imgSubmit.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);

        CommentActions.CommentActionListener actionListener = new CommentActions.CommentActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                mIsSubmittingReply = false;

                if (hasActivity()) {
                    editComment.setEnabled(true);
                    imgSubmit.setVisibility(View.VISIBLE);
                    progress.setVisibility(View.GONE);

                    if (succeeded) {
                        if (mChangeListener != null)
                            mChangeListener.onCommentAdded();
                        MessageBarUtils.showMessageBar(getActivity(), getString(R.string.note_reply_successful));
                        editComment.setText(null);
                    } else {
                        ToastUtils.showToast(getActivity(), R.string.reply_failed, ToastUtils.Duration.LONG);
                        // refocus editor on failure and show soft keyboard
                        editComment.requestFocus();
                        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(editComment, InputMethodManager.SHOW_IMPLICIT);
                    }
                }
            }
        };

        mIsSubmittingReply = true;
        CommentActions.submitReplyToComment(WordPress.currentBlog,
                                            mComment,
                                            replyText,
                                            actionListener);
    }

    /*
     * display the comment associated with the passed notification
     */
    private void showCommentForNote(Note note) {
        /*
         * in order to get the actual comment from a notification we need to extract the
         * blogId/postId/commentId from the notification, and this info is buried in the
         * "actions" array of the note's JSON. each action entry contains a "params"
         * array which contains these IDs, so find the first action then extract the IDs
         * from its params
         */
        Map<String,JSONObject> actions = note.getActions();
        if (actions.size() > 0) {
            String firstKey = actions.keySet().iterator().next();
            JSONObject jsonAction = actions.get(firstKey);
            JSONObject jsonParams = jsonAction.optJSONObject("params");
            if (jsonParams != null) {
                int blogId = jsonParams.optInt("blog_id");
                setBlogId(blogId);
                //int postId = jsonParams.optInt("post_id");
                int commentId = jsonParams.optInt("comment_id");

                String icon = note.getIconURL();
                final URI iconURI = (TextUtils.isEmpty(icon) ? null : URI.create(icon));

                // first try to get from local db, if that fails request it from the server
                Comment comment = WordPress.wpDB.getComment(mAccountId, commentId);
                if (comment != null) {
                    comment.profileImageUrl = iconURI;
                    setComment(comment);
                } else {
                    requestComment(blogId, commentId, iconURI);
                }
            }
        }
    }

    /*
     * request a comment via the REST API
     */
    private void requestComment(int blogId, int commentId, final URI profileImageURI) {
        final ProgressBar progress = (hasActivity() ? (ProgressBar) getActivity().findViewById(R.id.progress_loading) : null);
        if (progress != null)
            progress.setVisibility(View.VISIBLE);

        RestRequest.Listener restListener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                mIsRequestingComment = false;
                if (hasActivity()) {
                    if (progress != null)
                        progress.setVisibility(View.GONE);
                    Comment comment = new Comment(jsonObject);
                    if (comment != null) {
                        if (profileImageURI != null)
                            comment.profileImageUrl = profileImageURI;
                        WordPress.wpDB.addComment(mAccountId, comment);
                        setComment(comment);
                    }
                }
            }
        };
        RestRequest.ErrorListener restErrListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                mIsRequestingComment = false;
                if (hasActivity()) {
                    if (progress != null)
                        progress.setVisibility(View.GONE);
                    // TODO: needs a better error string
                    ToastUtils.showToast(getActivity(), R.string.connection_error, ToastUtils.Duration.LONG);
                }
            }
        };

        final String path = String.format("/sites/%s/comments/%s", blogId, commentId);
        mIsRequestingComment = true;
        WordPress.restClient.get(path, restListener, restErrListener);
    }
}

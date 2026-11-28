"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { authStore, captureSession } from "@/app/api/auth";
import { previewImage } from "@/app/api/submissions";

export default function PrivatePreview({ attachment, submission }: { attachment: string; submission?: string }) {
    const [url, setUrl] = useState("");
    const [failed, setFailed] = useState(false);
    useEffect(() => {
        const origin = captureSession();
        const current = () => !request.signal.aborted && origin.generation === authStore.snapshot().generation;
        const request = new AbortController();
        let localUrl = "";
        void previewImage(attachment, submission, request.signal).then(blob => {
            if (!current()) return;
            localUrl = URL.createObjectURL(blob);
            setUrl(localUrl);
        }).catch(() => { if (current()) setFailed(true); });
        return () => { request.abort(); if (localUrl) URL.revokeObjectURL(localUrl); };
    }, [attachment, submission]);
    return failed ? <p role="status" className="text-sm text-stone-600">이미지 미리보기를 불러올 수 없습니다.</p> :
        url ? <Image unoptimized src={url} alt="비공개 제보 이미지" width={240} height={180}
            className="h-auto max-h-48 w-auto max-w-full rounded border border-stone-200 object-contain" /> :
            <p role="status" className="text-sm text-stone-600">이미지 확인 중…</p>;
}

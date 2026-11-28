"use client";

import { useRef } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { submitSake, submissionsPath, type SubmissionInput } from "@/app/api/submissions";
import { useAuth } from "@/components/auth/AuthProvider";
import SubmissionForm from "./SubmissionForm";

export default function NewSubmission() {
    const router = useRouter();
    const cache = useQueryClient();
    const { member } = useAuth();
    const keys = useRef(new Map<string, string>());
    async function save(data: SubmissionInput) {
        const content = JSON.stringify(data);
        const key = keys.current.get(content) ?? crypto.randomUUID();
        keys.current.set(content,key);
        const result = await submitSake(data,key);
        void cache.invalidateQueries({ queryKey: ["my-submissions", member?.memberNo] });
        router.replace(`${submissionsPath}/${result.id}`);
    }
    return <SubmissionForm onSave={save} />;
}

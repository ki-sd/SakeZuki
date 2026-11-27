"use client";

import Image from "next/image";
import {useState} from "react";

const placeholder = "/images/sake-placeholder.png";

interface SakeImageProps {
    src: string | null;
    alt: string;
    fill?: boolean;
}

export default function SakeImage({src, alt, fill = true}: SakeImageProps) {
    const source = src || placeholder;
    // A source change retires the old request and its error state together.
    return <ImageRequest key={source} src={source} alt={alt} fill={fill}/>;
}

function ImageRequest({src, alt, fill}: {src: string; alt: string; fill: boolean}) {
    const [failed, setFailed] = useState(false);
    const usingPlaceholder = failed || src === placeholder;
    const dimensions = fill ? {fill: true} : {width: 280, height: 350};
    return (
        <Image src={usingPlaceholder ? placeholder : src}
               alt={alt}
               {...dimensions}
               unoptimized
               onError={usingPlaceholder ? undefined : () => setFailed(true)}
               className={fill ? "object-contain" : "h-full w-full object-contain"}/>
    );
}
